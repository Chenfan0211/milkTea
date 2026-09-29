package com.wuling.finance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wuling.common.api.PageResult;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.finance.entity.Withdrawal;
import com.wuling.finance.mapper.WithdrawalMapper;
import com.wuling.finance.port.PayoutPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 提现闭环。
 * 规则（对齐 user-h5/data/role-mock.js withdrawRule）：
 * - 单笔 ≤ 即时额度（默认 100 元）小额即时到账，无需人工审核；
 * - 超过即时额度需后台审核，审核通过后出款；
 * - 失败或驳回自动解冻对应金额；
 * - 申请时冻结金额，成功扣减，失败解冻。
 */
@Service
public class WithdrawalService {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 即时到账额度（分）：100 元 */
    public static final long INSTANT_LIMIT = 10000L;

    public static final String APPLIED = "APPLIED";
    public static final String AUDITING = "AUDITING";
    public static final String APPROVED = "APPROVED";
    public static final String PAID = "PAID";
    public static final String REJECTED = "REJECTED";
    public static final String FAILED = "FAILED";
    /** 转账已受理、结果待定（对接微信商家转账后新增） */
    public static final String PROCESSING = "PROCESSING";

    private final WithdrawalMapper withdrawalMapper;
    private final LedgerService ledgerService;
    private final PayoutPort payoutPort;

    public WithdrawalService(WithdrawalMapper withdrawalMapper,
                             LedgerService ledgerService,
                             PayoutPort payoutPort) {
        this.withdrawalMapper = withdrawalMapper;
        this.ledgerService = ledgerService;
        this.payoutPort = payoutPort;
    }

    /** 申请提现：冻结金额，小额即时到账，大额进入审核 */
    @Transactional(rollbackFor = Exception.class)
    public Withdrawal apply(Long userId, Long subjectId, String roleType, long amount) {
        if (amount <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "提现金额必须大于 0");
        }

        Withdrawal w = new Withdrawal();
        w.setWithdrawNo(nextNo());
        w.setUserId(userId);
        w.setSubjectId(subjectId);
        w.setRoleType(roleType);
        w.setAmount(amount);
        w.setFee(0L);
        w.setApplyTime(LocalDateTime.now());

        if (amount <= INSTANT_LIMIT) {
            // 小额即时：先置待出款，由 executePayout 据通道决定 PAID 或 PROCESSING
            // （payTime 只在确认到账后由 executePayout/confirmPaid 回写，此处不预设）
            w.setStatus(PAID);
            w.setReviewTime(LocalDateTime.now());
        } else {
            w.setStatus(APPLIED);
        }
        withdrawalMapper.insert(w);

        freeze(w, amount);
        if (PAID.equals(w.getStatus())) {
            // 小额即时：发起出款（mock 通道立即成功；wxpay 通道受理后等回调）
            executePayout(w);
        }
        log.info("withdrawal apply no={} amount={} status={}", w.getWithdrawNo(), amount, w.getStatus());
        return w;
    }

    /** 后台审核：通过则出款，驳回则解冻 */
    @Transactional(rollbackFor = Exception.class)
    public Withdrawal review(Long id, boolean approve, String reason) {
        Withdrawal w = withdrawalMapper.selectById(id);
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
        Withdrawal w = withdrawalMapper.selectById(id);
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
     * 发起出款（提现 → 微信商家转账）。
     *
     * <p><b>状态收敛（资金安全核心）</b>：
     * <ul>
     *   <li>mock 通道（immediatePaid=true）→ 立即 {@link #settlePaid} 记账 + 置 PAID；</li>
     *   <li>wxpay 通道（immediatePaid=false）→ 置 PROCESSING，等回调/查询再置 PAID 或 FAILED；</li>
     *   <li>受理失败 → 置 FAILED 并自动解冻。</li>
     * </ul>
     *
     * <p>无论哪种通道，都<b>不再</b>在未收到微信确认前同步置 PAID。
     */
    private void executePayout(Withdrawal w) {
        PayoutPort.PayoutResult result = payoutPort.transfer(
                w.getWithdrawNo(), w.getAmount(), w.getUserId(), "五零时光提现");

        if (!result.accepted()) {
            // 受理失败：置 FAILED 并解冻
            w.setStatus(FAILED);
            w.setFailureReason(result.failReason());
            withdrawalMapper.updateById(w);
            unfreeze(w, "提现出款受理失败，金额已解冻");
            log.warn("withdrawal payout rejected no={} reason={}", w.getWithdrawNo(), result.failReason());
            return;
        }

        if (result.immediatePaid()) {
            // mock 通道：即时到账，维持原记账行为
            settlePaid(w);
            w.setStatus(PAID);
            w.setPayTime(LocalDateTime.now());
            withdrawalMapper.updateById(w);
        } else {
            // wxpay 通道：已受理，结果待定
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
    private Withdrawal findByNo(String withdrawNo) {
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

    private String nextNo() {
        return "WD" + LocalDateTime.now().format(FMT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }
}
