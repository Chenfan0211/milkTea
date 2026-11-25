package com.wuling.finance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wuling.common.api.PageResult;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.finance.entity.Withdrawal;
import com.wuling.finance.mapper.WithdrawalMapper;
import com.wuling.finance.port.PayoutAccountPort;
import com.wuling.finance.port.PayoutPort;
import com.wuling.subject.onboard.MerchantOnboardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 提现闭环。
 *
 * <p>规则：所有提现申请先冻结余额并进入运营后台审核，审核通过后发起出款；
 * - 失败或驳回自动解冻对应金额；
 * - 申请时冻结金额，成功扣减，失败解冻。
 *
 * <p><b>双通道出款</b>：
 * <ul>
 *   <li>WXPAY：微信「商家转账到零钱」，按 openid 打款到零钱；</li>
 *   <li>YEEPAY：易宝「自助结算」，把经营者子商户的待结算余额结算到其结算卡。</li>
 * </ul>
 * 通道由 {@code app.pay.payout-channel} 决定，但<b>必须与资金来源渠道一致</b>
 * （微信收的钱只能微信出，易宝收的钱只能易宝出），否则会拿别处的钱垫付。
 *
 * <p><b>易宝前置闸门</b>：入驻 COMPLETED + 协议已签 + 结算卡已绑 + 该渠道可用额充足。
 * 未满足时拒绝并给出明确的补办指引，而不是等到打款失败才发现。
 */
@Service
public class WithdrawalService {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 兼容旧客户端的规则字段：0 表示无免审额度。 */
    public static final long INSTANT_LIMIT = 0L;

    public static final String APPLIED = "APPLIED";
    public static final String AUDITING = "AUDITING";
    public static final String APPROVED = "APPROVED";
    public static final String PAID = "PAID";
    public static final String REJECTED = "REJECTED";
    public static final String FAILED = "FAILED";
    /** 转账已受理、结果待定（对接微信商家转账 / 易宝结算后使用） */
    public static final String PROCESSING = "PROCESSING";

    /** 资金来源与出款渠道路由：微信收的钱走微信，易宝收的钱走易宝。 */
    public static final String PAYOUT_CHANNEL_WXPAY = "WXPAY";
    public static final String PAYOUT_CHANNEL_YEEPAY = "YEEPAY";

    private final WithdrawalMapper withdrawalMapper;
    private final LedgerService ledgerService;
    private final PayoutPort payoutPort;
    /** 易宝收款账户（结算卡）端口；未启用易宝时为空实现，不做任何校验。 */
    private final PayoutAccountPort payoutAccountPort;
    /** 入驻状态查询；未启用易宝时可缺省（单元测试构造场景）。 */
    private final ObjectProvider<MerchantOnboardService> onboardServiceProvider;
    private final ChannelBalanceService channelBalanceService;

    /** 分账明细留档（可为 null：单元测试与未启用易宝时不需要） */
    private final com.wuling.finance.mapper.WithdrawalDivideDetailMapper withdrawalDivideDetailMapper;
    /** 结算记录（读已分账订单用于留档；可为 null） */
    private final com.wuling.finance.mapper.SettlementRecordMapper settlementRecordMapper;

    /** 出款通道（与 trade-service 同名配置项）。 */
    private final String payoutChannel;

    public WithdrawalService(WithdrawalMapper withdrawalMapper,
                             LedgerService ledgerService,
                             PayoutPort payoutPort,
                             PayoutAccountPort payoutAccountPort,
                             ObjectProvider<MerchantOnboardService> onboardServiceProvider,
                             ChannelBalanceService channelBalanceService,
                             @Value("${app.pay.payout-channel:${app.pay.channel:mock}}") String payoutChannel,
                             org.springframework.beans.factory.ObjectProvider<
                                     com.wuling.finance.mapper.WithdrawalDivideDetailMapper> divideDetailMapperProvider,
                             org.springframework.beans.factory.ObjectProvider<
                                     com.wuling.finance.mapper.SettlementRecordMapper> settlementRecordMapperProvider) {
        this.withdrawalMapper = withdrawalMapper;
        this.ledgerService = ledgerService;
        this.payoutPort = payoutPort;
        this.payoutAccountPort = payoutAccountPort;
        this.onboardServiceProvider = onboardServiceProvider;
        this.channelBalanceService = channelBalanceService;
        this.payoutChannel = payoutChannel;
        this.withdrawalDivideDetailMapper = divideDetailMapperProvider.getIfAvailable();
        this.settlementRecordMapper = settlementRecordMapperProvider.getIfAvailable();
    }

    /** 申请提现：冻结金额，全部进入运营后台审核。 */
    @Transactional(rollbackFor = Exception.class)
    public Withdrawal apply(Long userId, Long subjectId, String roleType, long amount) {
        if (amount <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "提现金额必须大于 0");
        }
        String payoutChannel = resolvePayoutChannel();

        Withdrawal w = new Withdrawal();
        w.setWithdrawNo(nextNo());
        w.setUserId(userId);
        w.setSubjectId(subjectId);
        w.setRoleType(roleType);
        w.setAmount(amount);
        w.setFee(0L);
        w.setApplyTime(LocalDateTime.now());
        w.setPayoutChannel(payoutChannel);
        // 资金来源渠道：当前单渠道部署下与出款通道恒等（刻意的简化）。
        // 真正的多渠道路由（微信收的钱只能微信出）需要调用方在申请时显式指定
        // fundChannel，并由它推导 payoutChannel，而不是反过来。
        // 不要据此注释认为「已支持多渠道路由」—— 目前只保证单通道内不跨池。
        w.setFundChannel(payoutChannel);
        w.setStatus(APPLIED);

        if (PAYOUT_CHANNEL_YEEPAY.equals(payoutChannel)) {
            // 易宝通道：先过闸门再冻结，避免「冻了钱才发现没入驻」来回折腾
            gateYeepay(subjectId, amount);
            w.setPayeeAccountId(payoutAccountPort.defaultAccountId(subjectId));
            w.setPayeeSnapshot(payoutAccountPort.snapshot(subjectId));
        } else if (PAYOUT_CHANNEL_WXPAY.equals(payoutChannel)) {
            // 微信通道：同样要按渠道校验，防止「易宝的钱被微信出款」跨池垫付
            long wxAvailable = channelBalanceService.wxpayWithdrawable(subjectId);
            if (wxAvailable < amount) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "微信渠道可提现余额不足，当前可提 " + wxAvailable + " 分");
            }
            channelBalanceService.reconcileCheck(subjectId);
        }

        withdrawalMapper.insert(w);
        freeze(w, amount);
        if (PAYOUT_CHANNEL_YEEPAY.equals(payoutChannel)) {
            snapshotDivideDetails(w);
        }
        log.info("withdrawal apply no={} amount={} channel={} status={}",
                w.getWithdrawNo(), amount, payoutChannel, w.getStatus());
        return w;
    }

    /** 后台审核：通过则发起出款，驳回则解冻 */
    @Transactional(rollbackFor = Exception.class)
    public Withdrawal review(Long id, boolean approve, String reason) {
        Withdrawal w = withdrawalMapper.selectByIdForUpdate(id);
        if (w == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "提现申请不存在");
        }
        if (!APPLIED.equals(w.getStatus()) && !AUDITING.equals(w.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该提现申请已处理，不能重复审核");
        }
        if (approve) {
            // 审核通过：发起出款。executePayout 会据通道决定「即时到账」或「受理待回调」，
            // 并回写状态（PAID / PROCESSING / FAILED），这里不再直接置 PAID。
            w.setReviewTime(LocalDateTime.now());
            executePayout(w);
        } else {
            w.setStatus(REJECTED);
            w.setReviewTime(LocalDateTime.now());
            w.setFailureReason(reason);
            withdrawalMapper.updateById(w);
            unfreeze(w, "提现审核驳回，金额已解冻");
        }
        return w;
    }

    /** 出款失败：解冻 */
    @Transactional(rollbackFor = Exception.class)
    public Withdrawal markFailed(Long id, String reason) {
        Withdrawal w = withdrawalMapper.selectByIdForUpdate(id);
        if (w == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "提现申请不存在");
        }
        if (PAID.equals(w.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "已出款，不能标记失败");
        }
        w.setStatus(FAILED);
        w.setFailureReason(reason);
        withdrawalMapper.updateById(w);
        unfreeze(w, "提现出款失败，金额已解冻");
        return w;
    }

    /** 按 ID 查询（供审计记录变更前状态使用） */
    public Withdrawal getById(Long id) {
        return id == null ? null : withdrawalMapper.selectById(id);
    }

    public PageResult<Withdrawal> page(long current, long size, String status) {
        LambdaQueryWrapper<Withdrawal> query = new LambdaQueryWrapper<Withdrawal>().orderByDesc(Withdrawal::getId);
        if (status != null && !status.isBlank()) {
            query.eq(Withdrawal::getStatus, status.toUpperCase());
        }
        Page<Withdrawal> page = withdrawalMapper.selectPage(new Page<>(current, size), query);
        return PageResult.of(page.getRecords(), page.getCurrent(), page.getSize(), page.getTotal());
    }

    /**
     * 小程序端「我的提现记录」：按申请人过滤。
     *
     * <p>修正历史缺陷：原实现不按 subject/user 过滤，等于把全平台提现记录
     * 返回给任意登录用户，属于越权读。此处按 {@code userId} 强过滤。
     */
    public PageResult<Withdrawal> pageByUser(Long userId, long current, long size, String status) {
        LambdaQueryWrapper<Withdrawal> query = new LambdaQueryWrapper<Withdrawal>()
                .eq(Withdrawal::getUserId, userId)
                .orderByDesc(Withdrawal::getId);
        if (status != null && !status.isBlank()) {
            query.eq(Withdrawal::getStatus, status.toUpperCase());
        }
        Page<Withdrawal> page = withdrawalMapper.selectPage(new Page<>(current, size), query);
        return PageResult.of(page.getRecords(), page.getCurrent(), page.getSize(), page.getTotal());
    }

    /** 申请提现：可用余额转入冻结余额，并写入统一台账快照。 */
    private void freeze(Withdrawal w, long amount) {
        try {
            ledgerService.post(new LedgerService.Posting(w.getSubjectId(),
                    w.getRoleType(),
                    "FREEZE",
                    amount,
                    LedgerService.BUCKET_FROZEN,
                    null,
                    null,
                    null,
                    "WITHDRAWAL",
                    w.getWithdrawNo(),
                    null,
                    "提现申请冻结"));
        } catch (LedgerService.InsufficientBalanceException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "可提现余额不足");
        }
    }

    /**
     * 易宝出款闸门：入驻、签约、结算卡、渠道可用额。
     *
     * <p>四项任意缺失都抛出带指引的 {@link BusinessException}——
     * 让经营者知道该去补什么，而不是只看到「提现失败」。
     */
    private void gateYeepay(Long subjectId, long amount) {
        MerchantOnboardService onboard = onboardServiceProvider.getIfAvailable();
        if (onboard == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "易宝出款通道未启用");
        }
        if (!onboard.isOnboarded(subjectId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "请先完成商户入驻与协议签署，审核通过后方可提现");
        }
        if (payoutAccountPort.defaultAccountId(subjectId) == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请先绑定结算银行卡");
        }
        long available = channelBalanceService.yeepayWithdrawable(subjectId);
        if (available < amount) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "易宝渠道可提现余额不足，当前可提 " + available + " 分");
        }
    }

    /**
     * 发起出款。
     *
     * <p><b>状态收敛（资金安全核心）</b>：
     * <ul>
     *   <li>mock 通道（immediatePaid=true）→ 立即 {@link #settlePaid} 记账 + 置 PAID；</li>
     *   <li>wxpay / yeepay 通道（immediatePaid=false）→ 置 PROCESSING，等回调/查询再收敛；</li>
     *   <li>受理失败 → 置 FAILED 并自动解冻。</li>
     * </ul>
     */
    private void executePayout(Withdrawal w) {
        String channel = w.getPayoutChannel() == null ? resolvePayoutChannel() : w.getPayoutChannel();
        PayoutPort.Payee payee = PAYOUT_CHANNEL_YEEPAY.equals(channel)
                ? PayoutPort.Payee.ofYeepay(w.getSubjectId(),
                        payoutAccountPort.yeepayMerchantNo(w.getSubjectId()),
                        payoutAccountPort.yeepaySettleCardId(w.getSubjectId()))
                : PayoutPort.Payee.ofWx(w.getUserId(), w.getSubjectId());

        PayoutPort.PayoutResult result = payoutPort.transfer(
                w.getWithdrawNo(), w.getAmount(), payee, "五零时光提现");

        if (!result.accepted()) {
            w.setStatus(FAILED);
            w.setFailureReason(result.failReason());
            withdrawalMapper.updateById(w);
            unfreeze(w, "提现出款受理失败，金额已解冻");
            log.warn("withdrawal payout rejected no={} channel={} reason={}",
                    w.getWithdrawNo(), channel, result.failReason());
            return;
        }

        if (result.immediatePaid()) {
            // mock 通道：即时到账，维持原记账行为
            settlePaid(w);
            w.setStatus(PAID);
            w.setPayTime(LocalDateTime.now());
            withdrawalMapper.updateById(w);
        } else {
            // 真实通道：已受理，结果待定
            w.setStatus(PROCESSING);
            w.setTransferBatchNo(result.batchNo());
            withdrawalMapper.updateById(w);
        }
    }

    /** 回调/查询确认到账：扣减冻结余额并置 PAID（幂等，仅 PROCESSING 可转）。 */
    @Transactional(rollbackFor = Exception.class)
    public void confirmPaid(String withdrawNo) {
        Withdrawal w = findByNo(withdrawNo);
        if (w == null || PAID.equals(w.getStatus())) {
            return;
        }
        if (!PROCESSING.equals(w.getStatus())) {
            log.warn("提现确认到账被拒绝 no={} status={}", withdrawNo, w.getStatus());
            return;
        }
        settlePaid(w);
        w.setStatus(PAID);
        w.setPayTime(LocalDateTime.now());
        w.setCallbackTime(LocalDateTime.now());
        withdrawalMapper.updateById(w);
    }

    /** 回调/查询确认失败：解冻并置 FAILED（幂等，仅 PROCESSING 可转）。 */
    @Transactional(rollbackFor = Exception.class)
    public void confirmFailed(String withdrawNo, String reason) {
        Withdrawal w = findByNo(withdrawNo);
        if (w == null || FAILED.equals(w.getStatus()) || PAID.equals(w.getStatus())) {
            return;
        }
        if (!PROCESSING.equals(w.getStatus())) {
            log.warn("提现确认失败被拒绝 no={} status={}", withdrawNo, w.getStatus());
            return;
        }
        w.setStatus(FAILED);
        w.setFailureReason(reason);
        w.setTransferFailMsg(reason);
        withdrawalMapper.updateById(w);
        unfreeze(w, "提现出款失败，金额已解冻");
    }

    /** 按提现单号查询（内部用）。 */
    public Withdrawal findByNo(String withdrawNo) {
        return withdrawalMapper.selectOne(new LambdaQueryWrapper<Withdrawal>()
                .eq(Withdrawal::getWithdrawNo, withdrawNo));
    }

    /** 出款成功：冻结余额扣减，并写入统一台账快照。 */
    private void settlePaid(Withdrawal w) {
        try {
            ledgerService.post(new LedgerService.Posting(w.getSubjectId(),
                    w.getRoleType(),
                    "WITHDRAW",
                    -w.getAmount(),
                    LedgerService.BUCKET_FROZEN,
                    null,
                    null,
                    null,
                    "WITHDRAWAL",
                    w.getWithdrawNo(),
                    null,
                    "提现出款"));
        } catch (LedgerService.InsufficientBalanceException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "冻结金额异常，无法出款");
        }
    }

    /** 失败/驳回：冻结金额退回可用，并写入统一台账快照。 */
    private void unfreeze(Withdrawal w, String remark) {
        try {
            ledgerService.post(new LedgerService.Posting(w.getSubjectId(),
                    w.getRoleType(),
                    "UNFREEZE",
                    -w.getAmount(),
                    LedgerService.BUCKET_FROZEN,
                    null,
                    null,
                    null,
                    "WITHDRAWAL",
                    w.getWithdrawNo(),
                    null,
                    remark));
        } catch (LedgerService.InsufficientBalanceException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "解冻失败：冻结金额不足");
        }
    }

    /**
     * 当前生效的出款通道。
     *
     * <p>读 {@code app.pay.payout-channel}（缺省回落 {@code app.pay.channel}），
     * 与 trade-service 的 {@code PayoutGatewayResolver} 同一口径。
     */
    /**
     * 提现申请时快照「这笔提现由哪些订单的分账支撑」。
     *
     * <p>为什么要留档：结算记录会被后续退款冲正改写（金额减少 / 状态置 CANCELED），
     * 事后再查已无法还原当时的组成。本快照只写不改，供对账与纠纷追溯。
     *
     * <p>失败不阻断提现：这是审计留档，不是资金动作。
     */
    private void snapshotDivideDetails(Withdrawal w) {
        if (withdrawalDivideDetailMapper == null || settlementRecordMapper == null) {
            return;
        }
        try {
            List<com.wuling.finance.entity.SettlementRecord> records =
                    settlementRecordMapper.selectList(
                            new LambdaQueryWrapper<com.wuling.finance.entity.SettlementRecord>()
                                    .eq(com.wuling.finance.entity.SettlementRecord::getSubjectId,
                                            w.getSubjectId())
                                    .eq(com.wuling.finance.entity.SettlementRecord::getDivideStatus,
                                            com.wuling.finance.entity.SettlementRecord.DIVIDE_SUCCESS)
                                    .orderByAsc(com.wuling.finance.entity.SettlementRecord::getSettleDate));
            long remaining = w.getAmount();
            for (com.wuling.finance.entity.SettlementRecord record : records) {
                if (remaining <= 0L) {
                    break;
                }
                long available = Math.max(0L,
                        (record.getAmount() == null ? 0L : record.getAmount())
                                - (record.getRefundedAmount() == null ? 0L : record.getRefundedAmount()));
                if (available <= 0L) {
                    continue;
                }
                long take = Math.min(available, remaining);
                com.wuling.finance.entity.WithdrawalDivideDetail detail =
                        new com.wuling.finance.entity.WithdrawalDivideDetail();
                detail.setWithdrawalId(w.getId());
                detail.setWithdrawalNo(w.getWithdrawNo());
                detail.setSettlementRecordId(record.getId());
                detail.setOrderId(record.getOrderId());
                detail.setOrderNo(record.getOrderNo());
                detail.setDivideRequestId(record.getDivideRequestId());
                detail.setDivideAmount(take);
                detail.setDivideStatus(record.getDivideStatus());
                detail.setUniqueDivideNo(record.getUniqueDivideNo());
                withdrawalDivideDetailMapper.insert(detail);
                remaining -= take;
            }
        } catch (Exception e) {
            log.warn("提现分账明细留档失败（不影响提现）withdrawNo={} err={}",
                    w.getWithdrawNo(), e.getMessage());
        }
    }

    private String resolvePayoutChannel() {
        String configured = payoutChannel;
        if (configured == null || configured.isBlank()) {
            return PAYOUT_CHANNEL_WXPAY;
        }
        return configured.trim().toUpperCase();
    }

    private String nextNo() {
        return "WD" + LocalDateTime.now().format(FMT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }
}
