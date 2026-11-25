package com.wuling.finance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.finance.entity.FundFlow;
import com.wuling.finance.entity.ReconcileIssue;
import com.wuling.finance.entity.SettlementRecord;
import com.wuling.finance.entity.SplitRule;
import com.wuling.finance.entity.SplitSnapshot;
import com.wuling.finance.entity.SubjectAccount;
import com.wuling.finance.mapper.FundFlowMapper;
import com.wuling.finance.mapper.SettlementRecordMapper;
import com.wuling.finance.mapper.SplitRuleMapper;
import com.wuling.finance.mapper.SplitSnapshotMapper;
import com.wuling.finance.mapper.SubjectAccountMapper;
import com.wuling.subject.port.SubjectQueryPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 分账与台账记账。
 * 规则：
 * - 新订单核销后生成不可变五方快照，并立即计入可用余额；
 * - 历史 PENDING 仍由兼容结算任务转入可用余额；
 * - 退款按原结算记录逐账户扣回，余额不足时整批回滚并记录对账异常。
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    public static final String SETTLE_PENDING = "PENDING";
    public static final String SETTLE_SETTLEABLE = "SETTLEABLE";
    public static final String SETTLE_SETTLED = "SETTLED";
    public static final String SETTLE_CANCELED = "CANCELED";

    public static final String BUCKET_AVAILABLE = "AVAILABLE";
    public static final String BUCKET_FROZEN = "FROZEN";
    /** 押金桶：不可提现、不可分账，仅用于退款冲正兜底。 */
    public static final String BUCKET_DEPOSIT = "DEPOSIT";

    private static final Set<String> FLOW_TYPES = Set.of(
            "INCOME", "WITHDRAW", "REFUND", "FREEZE", "UNFREEZE", "ADJUST",
            "DEPOSIT_SET", "DEPOSIT_DEDUCT");
    private static final Set<String> FUNDED_SETTLEMENT_STATUSES = Set.of(
            SETTLE_SETTLEABLE, SETTLE_SETTLED, "FROZEN");

    /**
     * 统一记账入参。
     *
     * <p>changeAmount 使用正负号表示余额桶增减，amount 入库时保存绝对值。
     * 账户冻结/解冻会同时调整 available 与 frozen 两个余额桶，但流水快照只记录
     * balanceBucket 指定的桶。
     */
    public record Posting(Long subjectId,
                          String roleType,
                          String type,
                          long changeAmount,
                          String balanceBucket,
                          Long orderId,
                          String orderNo,
                          Long settlementRecordId,
                          String bizType,
                          String bizNo,
                          String settlementStatus,
                          String remark) {
    }

    /** 账户余额不足，供退款外层事务识别并写入对账异常。 */
    public static class InsufficientBalanceException extends RuntimeException {
        private final Long subjectId;
        private final long available;
        private final long required;

        public InsufficientBalanceException(Long subjectId, long available, long required) {
            super("账户余额不足 subjectId=" + subjectId + " available=" + available + " required=" + required);
            this.subjectId = subjectId;
            this.available = available;
            this.required = required;
        }

        public Long getSubjectId() {
            return subjectId;
        }

        public long getAvailable() {
            return available;
        }

        public long getRequired() {
            return required;
        }
    }

    private final SplitRuleMapper splitRuleMapper;
    private final SplitSnapshotMapper splitSnapshotMapper;
    private final SubjectAccountMapper subjectAccountMapper;
    private final FundFlowMapper fundFlowMapper;
    private final SettlementRecordMapper settlementRecordMapper;
    private final ReconcileService reconcileService;
    private final TransactionTemplate transactionTemplate;
    /** 第 12 期：主体查询改走端口（本地实现，无网络开销） */
    private final SubjectQueryPort subjectQueryPort;
    private final SplitCalculator splitCalculator;
    private final com.wuling.finance.mapper.DepositRecordMapper depositRecordMapper;
    private final com.wuling.finance.divide.DivideRefundService divideRefundService;
    /**
     * 用于查询订单支付渠道（{@code payment.channel}）。
     *
     * <p>为什么直接 JDBC 而不是端口：只在核销时执行一次单表查询，
     * 为它建跨域端口不划算。
     */
    private final org.springframework.jdbc.core.JdbcTemplate paymentJdbcTemplate;

    /**
     * 兼容既有单元测试的构造（无 JdbcTemplate）。
     *
     * <p>生产用下面的 {@code @Autowired} 版本；本重载仅为不破坏
     * {@code LedgerServiceTest} 的 11 参构造调用。
     * 渠道查询在 jdbcTemplate 为空时降级为 WXPAY。
     */
    public LedgerService(SplitRuleMapper splitRuleMapper,
                         SplitSnapshotMapper splitSnapshotMapper,
                         SubjectAccountMapper subjectAccountMapper,
                         FundFlowMapper fundFlowMapper,
                         SettlementRecordMapper settlementRecordMapper,
                         ReconcileService reconcileService,
                         SubjectQueryPort subjectQueryPort,
                         SplitCalculator splitCalculator,
                         PlatformTransactionManager transactionManager,
                         com.wuling.finance.mapper.DepositRecordMapper depositRecordMapper,
                         org.springframework.beans.factory.ObjectProvider<com.wuling.finance.divide.DivideRefundService> divideRefundServiceProvider) {
        this(splitRuleMapper, splitSnapshotMapper, subjectAccountMapper, fundFlowMapper,
                settlementRecordMapper, reconcileService, subjectQueryPort, splitCalculator,
                transactionManager, depositRecordMapper, divideRefundServiceProvider, null);
    }

    /** 运行期构造函数（Spring 使用）。 */
    @Autowired
    public LedgerService(SplitRuleMapper splitRuleMapper,
                         SplitSnapshotMapper splitSnapshotMapper,
                         SubjectAccountMapper subjectAccountMapper,
                         FundFlowMapper fundFlowMapper,
                         SettlementRecordMapper settlementRecordMapper,
                         ReconcileService reconcileService,
                         SubjectQueryPort subjectQueryPort,
                         SplitCalculator splitCalculator,
                         PlatformTransactionManager transactionManager,
                         com.wuling.finance.mapper.DepositRecordMapper depositRecordMapper,
                         org.springframework.beans.factory.ObjectProvider<com.wuling.finance.divide.DivideRefundService> divideRefundServiceProvider,
                         org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.splitRuleMapper = splitRuleMapper;
        this.splitSnapshotMapper = splitSnapshotMapper;
        this.subjectAccountMapper = subjectAccountMapper;
        this.fundFlowMapper = fundFlowMapper;
        this.settlementRecordMapper = settlementRecordMapper;
        this.reconcileService = reconcileService;
        this.subjectQueryPort = subjectQueryPort;
        this.splitCalculator = splitCalculator;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.depositRecordMapper = depositRecordMapper;
        this.divideRefundService = divideRefundServiceProvider.getIfAvailable();
        this.paymentJdbcTemplate = jdbcTemplate;
    }

    public SplitRule resolveRule(Long productId) {
        if (productId != null) {
            SplitRule productRule = splitRuleMapper.selectOne(new LambdaQueryWrapper<SplitRule>()
                    .eq(SplitRule::getScope, "PRODUCT")
                    .eq(SplitRule::getProductId, productId)
                    .eq(SplitRule::getStatus, "enabled")
                    .last("limit 1"));
            if (productRule != null) {
                return productRule;
            }
        }
        return splitRuleMapper.selectOne(new LambdaQueryWrapper<SplitRule>()
                .eq(SplitRule::getScope, "GLOBAL")
                .eq(SplitRule::getStatus, "enabled")
                .last("limit 1"));
    }

    /**
     * 订单核销后分账：写不可变快照 + 各方已结算台账，并立即计入可用余额。
     * 幂等：同一订单已存在快照时直接返回。
     *
     * @param items 订单明细（含供应商归属与明细金额），用于供应商份额按明细分摊
     */
    @Transactional(rollbackFor = Exception.class)
    public SplitSnapshot executeSplit(Long orderId, String orderNo, long paidAmount, int itemCount,
                                      Long storeSubjectId, Long channelSubjectId, Long productId,
                                      long platformCommission, List<SplitCalculator.LineItem> items) {
        return executeSplit(orderId, orderNo, paidAmount, itemCount, storeSubjectId, channelSubjectId,
                productId, null, platformCommission, items);
    }

    @Transactional(rollbackFor = Exception.class)
    public SplitSnapshot executeSplit(Long orderId, String orderNo, long paidAmount, int itemCount,
                                      Long storeSubjectId, Long channelSubjectId, Long productId,
                                      Long splitRuleId, long platformCommission,
                                      List<SplitCalculator.LineItem> items) {
        return executeSplit(orderId, orderNo, paidAmount, itemCount, storeSubjectId, channelSubjectId,
                productId, splitRuleId, null, platformCommission, items);
    }

    @Transactional(rollbackFor = Exception.class)
    public SplitSnapshot executeSplit(Long orderId, String orderNo, long paidAmount, int itemCount,
                                      Long storeSubjectId, Long channelSubjectId, Long productId,
                                      Long splitRuleId, SplitRule ruleSnapshot, long platformCommission,
                                      List<SplitCalculator.LineItem> items) {
        return executeSplit(orderId, orderNo, paidAmount, itemCount, storeSubjectId, channelSubjectId,
                productId, splitRuleId, ruleSnapshot, platformCommission, items, false, null);
    }

    /**
     * 分账主入口（商城订单支持）：
     * <ul>
     *   <li>{@code mallOrder}=true 时仅平台、供应商和团长参与分账；门店、渠道、投资人固定为 0；</li>
     *   <li>分销佣金（Σ distributionCommission × 件数）优先计入 {@code leaderSubjectId} 主体；</li>
     *   <li>无团长主体时佣金自然留在平台尾差中（不单独立账）。</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public SplitSnapshot executeSplit(Long orderId, String orderNo, long paidAmount, int itemCount,
                                      Long storeSubjectId, Long channelSubjectId, Long productId,
                                      Long splitRuleId, SplitRule ruleSnapshot, long platformCommission,
                                      List<SplitCalculator.LineItem> items, boolean mallOrder,
                                      Long leaderSubjectId) {
        // 幂等快路径：已存在直接返回，避免重复计算。
        SplitSnapshot exists = splitSnapshotMapper.selectOne(new LambdaQueryWrapper<SplitSnapshot>()
                .eq(SplitSnapshot::getOrderId, orderId));
        if (exists != null) {
            log.info("split snapshot already exists for order {}, skip", orderNo);
            return exists;
        }

        // ②B：未绑定供应商的商品拒绝分账，避免资金归属不明
        List<Long> missing = items == null ? List.of() : items.stream()
                .filter(i -> i == null || i.supplierSubjectId() == null)
                .map(i -> orderId)
                .toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "订单存在未绑定供应商的商品，无法分账: " + orderNo);
        }

        SplitRule rule = mallOrder ? null
                : ruleSnapshot != null ? ruleSnapshot
                : (splitRuleId == null ? resolveRule(productId) : splitRuleMapper.selectById(splitRuleId));
        if (!mallOrder && rule == null) {
            throw new IllegalStateException("未配置分账规则，无法分账: " + orderNo);
        }
        boolean hasChannel = !mallOrder && channelSubjectId != null;
        // 投资人「当月达标后比例」：需先算出该投资人当月已累计分账额，
        // 再交给 SplitCalculator 决定本单用原比例还是达标后比例。
        // 未绑定投资人（investorOf 为 null）时不启用阈值判定。
        Long investorSubjectId = mallOrder ? null : investorOf(storeSubjectId);
        Long accumulatedInvestorAmount = mallOrder ? null : accumulatedThisMonth(investorSubjectId);
        SplitCalculator.SplitAmount amount = splitCalculator.calc(
                paidAmount, itemCount, hasChannel, rule, platformCommission,
                items == null ? List.of() : items, accumulatedInvestorAmount, mallOrder);
        if (mallOrder && leaderSubjectId == null && amount.leader() != 0L) {
            // 无归属团长时，成交价中的分销差额归平台；快照与实际入账保持一致。
            amount = new SplitCalculator.SplitAmount(
                    amount.platform() + amount.leader(), amount.store(), amount.channel(),
                    amount.investor(), amount.supplier(), amount.platformCommission(),
                    amount.platformBonus() + amount.leader(), 0L);
        }

        SplitSnapshot snapshot = new SplitSnapshot();
        snapshot.setSnapshotNo(nextNo("SN"));
        snapshot.setOrderId(orderId);
        snapshot.setOrderNo(orderNo);
        snapshot.setItemCount(itemCount);
        snapshot.setPlatformAmount(amount.platform());
        snapshot.setStoreAmount(amount.store());
        snapshot.setChannelAmount(amount.channel());
        snapshot.setInvestorAmount(amount.investor());
        snapshot.setSupplierAmount(amount.supplier());
        snapshot.setLeaderAmount(amount.leader());
        snapshot.setPlatformCommission(amount.platformCommission());
        snapshot.setPlatformBonus(amount.platformBonus());
        long sum = amount.platform() + amount.store() + amount.channel() + amount.investor() + amount.supplier() + amount.leader();
        if (sum != paidAmount) {
            throw new IllegalStateException("分账守恒校验失败 orderNo=" + orderNo
                    + " expected=" + paidAmount + " actual=" + sum);
        }
        snapshot.setTotalCheck("一致");
        snapshot.setStatus("valid");
        // 并发安全（第 0 期加固）：上面的「先查」只是快路径，真正的防重依赖
        // split_snapshot 唯一索引 uk_split_snapshot_order(order_id)。
        // 两笔并发核销同一订单时，后到者在此抛 DuplicateKeyException，
        // 回滚本次写入的台账，并返回已存在的那份快照（保证不重复分账）。
        try {
            splitSnapshotMapper.insert(snapshot);
        } catch (DuplicateKeyException e) {
            log.warn("concurrent split detected, reuse existing snapshot. orderNo={}", orderNo);
            SplitSnapshot duplicated = splitSnapshotMapper.selectOne(
                    new LambdaQueryWrapper<SplitSnapshot>().eq(SplitSnapshot::getOrderId, orderId));
            if (duplicated != null) {
                return duplicated;
            }
            throw e;
        }

        // 各方待结算台账（按明细汇总，供应商可多主体）。
        // 平台单独走 recordPlatformSettled（拆「分佣/剩余」两条流水），不进 credits。
        Map<Long, Long> credits = new LinkedHashMap<>();
        Long platformSubjectId = platformSubjectId();
        putIfNonZero(credits, storeSubjectId, amount.store());
        putIfNonZero(credits, channelSubjectId, amount.channel());
        putIfNonZero(credits, investorSubjectId, amount.investor());
        putIfNonZero(credits, leaderSubjectId, amount.leader());

        // 供应商按明细分别归集到各自主体（成本单价 × 件数，与快照 supplierAmount 同口径）
        if (items != null) {
            for (SplitCalculator.LineItem item : items) {
                if (item == null || item.supplierSubjectId() == null) {
                    continue;
                }
                long unitCost = item.costPrice() == null ? 0L : item.costPrice();
                Integer qty = item.quantity();
                long share = unitCost * (qty == null || qty <= 0 ? 1L : qty);
                putIfNonZero(credits, item.supplierSubjectId(), share);
            }
        }

        List<Map.Entry<Long, Long>> orderedCredits = credits.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .toList();
        for (Map.Entry<Long, Long> entry : orderedCredits) {
            recordSettled(snapshot, entry.getKey(), entry.getValue());
        }
        // 平台方：拆「平台分佣」与「平台剩余」两条流水（合计 = platformAmount，守恒不变）。
        recordPlatformSettled(snapshot, platformSubjectId, amount.platform(),
                amount.platformCommission(), amount.platformBonus());
        log.info("split done orderNo={} paid={} platform={} store={} channel={} investor={} supplier={}",
                orderNo, paidAmount, amount.platform(), amount.store(), amount.channel(),
                amount.investor(), amount.supplier());
        return snapshot;
    }

    /** 兼容旧签名（无明细分摊，供应商归属缺失时会因②B被拒绝） */
    @Transactional(rollbackFor = Exception.class)
    public SplitSnapshot executeSplit(Long orderId, String orderNo, long paidAmount, int itemCount,
                                      Long storeSubjectId, Long channelSubjectId, Long productId,
                                      long platformCommission) {
        return executeSplit(orderId, orderNo, paidAmount, itemCount, storeSubjectId,
                channelSubjectId, productId, platformCommission, List.of());
    }
    /** 新订单核销即结算：台账置 SETTLED，同事务写入可用余额及完整流水。 */
    private void recordSettled(SplitSnapshot snapshot, Long subjectId, long amount) {
        if (amount == 0L) {
            return;
        }
        SubjectQueryPort.SubjectView subject = subjectQueryPort.findById(subjectId);
        SettlementRecord record = new SettlementRecord();
        record.setRecordNo(nextNo("SR"));
        record.setSubjectId(subjectId);
        record.setSnapshotId(snapshot.getId());
        record.setOrderId(snapshot.getOrderId());
        // order_no 冗余落库：渠道可用额计算、易宝分账、退款归还都要按订单号关联，
        // 每次都 join split_snapshot 成本高且快照可能归档。
        record.setOrderNo(snapshot.getOrderNo());
        record.setAmount(amount);
        record.setOriginalAmount(amount);
        record.setRefundedAmount(0L);
        record.setStatus(SETTLE_SETTLED);
        record.setSettleDate(LocalDate.now());
        applyChannelInfo(record, snapshot.getOrderNo());
        settlementRecordMapper.insert(record);

        post(new Posting(subjectId,
                subject == null ? null : subject.getSubjectType(),
                amount > 0L ? "INCOME" : "ADJUST",
                amount,
                BUCKET_AVAILABLE,
                snapshot.getOrderId(),
                snapshot.getOrderNo(),
                record.getId(),
                "ORDER",
                snapshot.getOrderNo(),
                SETTLE_SETTLED,
                "订单核销分账入账"));
    }

    /**
     * 写入渠道归属与清算状态。
     *
     * <p><b>为什么在这里查</b>：{@code OrderVerifiedEvent} 不带支付渠道，
     * 给它加字段属于跨模块契约变更（影响 wuling-common + trade 发布端）。
     * 在核销时查一次 {@code payment.channel} 成本可接受，且改动面最小。
     *
     * <p><b>清算状态的差异</b>：
     * <ul>
     *   <li>微信：无清算概念，直接 {@code SUCCESS}；</li>
     *   <li>易宝：初始 {@code PENDING}，等清算通知到达才置 {@code SUCCESS}，
     *       期间分账会被门禁拦住（见 OrderDivideService）。</li>
     * </ul>
     */
    private void applyChannelInfo(SettlementRecord record, String orderNo) {
        String channel = resolveFundChannel(orderNo);
        record.setFundChannel(channel);
        record.setClearingStatus(SettlementRecord.CHANNEL_YEEPAY.equals(channel)
                ? SettlementRecord.CLEARING_PENDING
                : SettlementRecord.CLEARING_SUCCESS);
    }

    /**
     * 解析订单支付渠道。
     *
     * <p>查不到时按 {@code WXPAY} 兜底：易宝收款是本项目新增能力，
     * 历史订单与异常情况下默认走微信，避免误标为易宝导致错误分账。
     */
    private String resolveFundChannel(String orderNo) {
        if (orderNo == null || orderNo.isBlank() || paymentJdbcTemplate == null) {
            return SettlementRecord.CHANNEL_WXPAY;
        }
        try {
            List<String> rows = paymentJdbcTemplate.queryForList(
                    "select UPPER(channel) from payment "
                            + "where order_no = ? and deleted = 0 limit 1",
                    String.class, orderNo);
            if (rows.isEmpty() || rows.get(0) == null || rows.get(0).isBlank()) {
                return SettlementRecord.CHANNEL_WXPAY;
            }
            return rows.get(0);
        } catch (Exception e) {
            log.warn("查询订单支付渠道失败，按 WXPAY 兜底 orderNo={} err={}", orderNo, e.getMessage());
            return SettlementRecord.CHANNEL_WXPAY;
        }
    }

    /**
     * 平台方分账入账：拆「平台分佣」与「平台剩余」两条流水。
     *
     * <p>数据库 split_snapshot 已区分 platformCommission（分佣）与 platformBonus（剩余），
     * 但 fund_flow 原先只落一条平台总额，导致流水页无法区分两部分收入。
     * 这里拆成两条 INCOME 流水，合计仍等于平台总额，守恒不变；
     * 结算记录也按两部分各写一条，便于对账逐方核对。
     *
     * <p>当剩余（bonus）为 0 时不落空流水，避免产生无意义的 0 元记录。
     */
    private void recordPlatformSettled(SplitSnapshot snapshot, Long platformSubjectId,
                                       long platformAmount, long commission, long bonus) {
        if (platformSubjectId == null || platformAmount == 0L) {
            return;
        }
        SubjectQueryPort.SubjectView subject = subjectQueryPort.findById(platformSubjectId);
        String roleType = subject == null ? null : subject.getSubjectType();
        if (platformAmount < 0L) {
            // 平台承担差额：落一条负向 ADJUST 流水，保持「平台剩余」语义不变（负数即承担）。
            recordSettledPart(snapshot, platformSubjectId, roleType, platformAmount, "平台承担差额");
            return;
        }
        // 平台收入为正：拆「平台分佣」与「平台剩余」两条 INCOME 流水。
        long commissionPart = Math.max(0L, commission);
        long bonusPart = platformAmount - commissionPart;
        if (bonusPart < 0L) {
            bonusPart = 0L;
        }
        if (commissionPart > 0L) {
            recordSettledPart(snapshot, platformSubjectId, roleType, commissionPart, "平台分佣");
        }
        if (bonusPart > 0L) {
            recordSettledPart(snapshot, platformSubjectId, roleType, bonusPart, "平台剩余");
        }
    }

    /** 按给定主体、金额与备注写一条结算记录 + 一条流水。 */
    private void recordSettledPart(SplitSnapshot snapshot, Long subjectId, String roleType,
                                   long amount, String remarkSuffix) {
        if (amount == 0L) {
            return;
        }
        SettlementRecord record = new SettlementRecord();
        record.setRecordNo(nextNo("SR"));
        record.setSubjectId(subjectId);
        record.setSnapshotId(snapshot.getId());
        record.setOrderId(snapshot.getOrderId());
        // order_no 冗余落库：渠道可用额计算、易宝分账、退款归还都要按订单号关联。
        record.setOrderNo(snapshot.getOrderNo());
        record.setAmount(amount);
        record.setOriginalAmount(amount);
        record.setRefundedAmount(0L);
        record.setStatus(SETTLE_SETTLED);
        record.setSettleDate(LocalDate.now());
        applyChannelInfo(record, snapshot.getOrderNo());
        settlementRecordMapper.insert(record);

        post(new Posting(subjectId,
                roleType,
                amount > 0L ? "INCOME" : "ADJUST",
                amount,
                BUCKET_AVAILABLE,
                snapshot.getOrderId(),
                snapshot.getOrderNo(),
                record.getId(),
                "ORDER",
                snapshot.getOrderNo(),
                SETTLE_SETTLED,
                "订单核销分账入账（" + remarkSuffix + "）"));
    }

    /** 兼容历史任务：旧 PENDING -> SETTLEABLE（计入可用余额）。 */
    @Transactional(rollbackFor = Exception.class)
    public int settleDue(LocalDate settleDate) {
        List<SettlementRecord> pendings = settlementRecordMapper.selectList(new LambdaQueryWrapper<SettlementRecord>()
                .eq(SettlementRecord::getStatus, SETTLE_PENDING)
                .le(SettlementRecord::getCreateTime, settleDate.plusDays(1).atStartOfDay())
                .orderByAsc(SettlementRecord::getId));
        int count = 0;
        for (SettlementRecord record : pendings) {
            record.setStatus(SETTLE_SETTLEABLE);
            record.setSettleDate(settleDate);
            settlementRecordMapper.updateById(record);
            SplitSnapshot snapshot = record.getSnapshotId() == null
                    ? null : splitSnapshotMapper.selectById(record.getSnapshotId());
            SubjectAccount account = ensureAccount(record.getSubjectId());
            post(new Posting(record.getSubjectId(),
                    account.getRoleType(),
                    "INCOME",
                    record.getAmount(),
                    BUCKET_AVAILABLE,
                    record.getOrderId(),
                    snapshot == null ? null : snapshot.getOrderNo(),
                    record.getId(),
                    "ORDER",
                    snapshot == null ? null : snapshot.getOrderNo(),
                    SETTLE_SETTLEABLE,
                    "历史待结算转为可结算"));
            count++;
        }
        return count;
    }

    /**
     * 退款冲正。历史 PENDING 只取消台账；已入账记录逐账户扣回可用余额。
     *
     * <p>本方法刻意不放在外层事务中：任一账户余额不足时，内部记账事务整体回滚，
     * 随后由独立事务写入 reconcile_issue，保证不会留下半批成功流水或负余额。
     */
    public void reverseForOrder(String orderNo, String refundNo) {
        reverseForOrder(orderNo, refundNo, 10000);
    }

    /**
     * 退款冲正（支持比例）：{@code refundRatio} 为本次退款/订单实付（万分比），
     * 缺省 10000 全退。按比例对订单各主体结算记录扣回对应份额（含团长佣金、平台、供应商、投资人）。
     */
    public void reverseForOrder(String orderNo, String refundNo, int refundRatio) {
        Long orderId = selectOrderId(orderNo);
        if (orderId == null) {
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> reverseInTransaction(orderId, orderNo, refundNo, refundRatio, null));
        } catch (InsufficientBalanceException e) {
            reconcileService.recordOnce(ReconcileIssue.TYPE_REFUND_BALANCE_SHORTAGE,
                    orderNo,
                    refundNo,
                    "账户可用余额=" + e.getAvailable(),
                    "应扣回=" + e.getRequired(),
                    e.getRequired() - e.getAvailable());
            log.error("退款冲正余额不足，已整批回滚并记录对账异常 orderNo={} refundNo={} subjectId={} available={} required={}",
                    orderNo, refundNo, e.getSubjectId(), e.getAvailable(), e.getRequired());
        }
    }

    /** 商城按退货明细精确冲正，避免部分退款按整单比例误扣其他供应商。 */
    public void reverseMallAfterSale(String orderNo, String refundNo, int refundRatio,
                                     Map<Long, Long> subjectRefundAmounts,
                                     long platformCommissionRefund, long platformBonusRefund,
                                     long platformCommissionOriginal, long platformBonusOriginal) {
        Long orderId = selectOrderId(orderNo);
        if (orderId == null) return;
        try {
            transactionTemplate.executeWithoutResult(status -> reverseInTransaction(orderId, orderNo, refundNo,
                    refundRatio, new MallRefundShares(subjectRefundAmounts, platformCommissionRefund,
                            platformBonusRefund, platformCommissionOriginal, platformBonusOriginal)));
        } catch (InsufficientBalanceException e) {
            reconcileService.recordOnce(ReconcileIssue.TYPE_REFUND_BALANCE_SHORTAGE, orderNo, refundNo,
                    "账户可用余额=" + e.getAvailable(), "应扣回=" + e.getRequired(),
                    e.getRequired() - e.getAvailable());
        }
    }

    /** 兼容旧调用；新退款链路应携带 refundNo。 */
    public void reverseForOrder(String orderNo) {
        reverseForOrder(orderNo, null);
    }

    /**
     * 易宝渠道退款前先归还应退份额。
     *
     * <p>放在 {@code reverseForOrder} 的外层调用（由退款消费者调用），
     * 而不是在冲正事务内——归还是一次三方 HTTP 调用，放进事务会拉长事务
     * 且三方失败不该回滚本地冲正。
     *
     * @param orderId     订单 id
     * @param orderNo     订单号
     * @param refundNo    退款单号
     * @param refundRatio 退款比例（万分比）
     */
    /**
     * 【已废弃】退款前的分账归还已改为「归集参数随退款请求一起传」。
     *
     * <p><b>为什么改</b>：易宝的 {@code trade/refund} 自带
     * {@code divideBackDetail} 参数，一次请求即可完成归集 + 退款。
     * 原来「先调 divide/back 再退款」的两步做法多一个失败点、
     * 中间状态难处理，且归集参数需要明细级 {@code divideDetailNo}。
     *
     * <p>现在的归集明细由 {@code DivideRefundService#buildDivideBackDetail}
     * 在发起退款时构造，经 {@code /internal/yeepay/refund/divide-back-detail}
     * 提供给 trade-service。
     *
     * <p>本方法保留为空实现，仅为兼容既有调用点，
     * 新代码不应再调用。
     *
     * @deprecated 归集改由退款请求携带 divideBackDetail
     */
    @Deprecated
    public void returnDividedBeforeRefund(Long orderId, String orderNo, String refundNo, int refundRatio) {
        // 归集已并入退款请求，此处无需动作。
        // 保留方法签名避免一次性破坏调用方（OrderRefundedReverseConsumer）。
    }

    private record MallRefundShares(Map<Long, Long> subjects, long commissionRefund, long bonusRefund,
                                    long commissionOriginal, long bonusOriginal) { }

    private void reverseInTransaction(Long orderId, String orderNo, String refundNo, int refundRatio,
                                      MallRefundShares shares) {
        List<SettlementRecord> records = settlementRecordMapper.selectByOrderIdForUpdate(orderId);
        long safeRatio = refundRatio <= 0 ? 10000 : refundRatio;
        boolean fullReverse = shares == null && safeRatio >= 10000;
        Map<Long, Long> exact = shares == null ? null : mallRecordRefundShares(records, shares);
        for (SettlementRecord record : records) {
            if (SETTLE_CANCELED.equals(record.getStatus())) {
                if (exact != null && exact.getOrDefault(record.getId(), 0L) != 0L) {
                    throw new IllegalStateException("已冲正结算记录仍有退货份额 recordId=" + record.getId());
                }
                continue;
            }
            if (SETTLE_PENDING.equals(record.getStatus()) && fullReverse) {
                // 历史待结算尚未进入任何余额桶，只取消，不伪造退款流水。
                if (settlementRecordMapper.updateStatusIfCurrent(
                        record.getId(), SETTLE_PENDING, SETTLE_CANCELED) != 1) {
                    throw new IllegalStateException("结算记录状态流转失败 recordId=" + record.getId());
                }
                record.setStatus(SETTLE_CANCELED);
                continue;
            }
            if (!SETTLE_PENDING.equals(record.getStatus()) && !FUNDED_SETTLEMENT_STATUSES.contains(record.getStatus())) {
                throw new IllegalStateException("未知结算状态，无法退款冲正: " + record.getStatus());
            }
            if (record.getAmount() == null) {
                throw new IllegalStateException("结算金额非法，无法退款冲正 recordId=" + record.getId());
            }
            if (record.getAmount() == 0L) {
                if (exact != null && exact.getOrDefault(record.getId(), 0L) != 0L) {
                    throw new IllegalStateException("退货份额超过已冲正结算金额 recordId=" + record.getId());
                }
                if (fullReverse && settlementRecordMapper.updateStatusIfCurrent(
                        record.getId(), record.getStatus(), SETTLE_CANCELED) != 1) {
                    throw new IllegalStateException("零余额结算记录状态流转失败 recordId=" + record.getId());
                }
                if (fullReverse) record.setStatus(SETTLE_CANCELED);
                continue;
            }

            long remainingAmount = record.getAmount();
            long originalAmount = record.getOriginalAmount() == null
                    ? remainingAmount + nz(record.getRefundedAmount()) : record.getOriginalAmount();
            long refundedAmount = nz(record.getRefundedAmount());
            // refundRatio 是本次退款占订单实付的比例；每次按原结算金额计算份额，
            // 再与已冲正累计额相加，不能拿扣减后的剩余金额再乘比例。
            long deduct = exact == null ? Math.round(originalAmount * safeRatio / 10000.0)
                    : exact.getOrDefault(record.getId(), 0L);
            if (exact != null && deduct != 0L && (Long.signum(deduct) != Long.signum(remainingAmount)
                    || Math.abs(deduct) > Math.abs(remainingAmount))) {
                throw new IllegalStateException("退货冲正份额超过结算记录余额 recordId=" + record.getId());
            }
            if (originalAmount >= 0L && exact == null) {
                // 正分账记录：部分退款按剩余金额封顶，避免累计冲正超过原金额。
                deduct = Math.min(deduct, Math.max(0L, remainingAmount));
                if (deduct <= 0L) {
                    continue;
                }
            } else if (exact == null && originalAmount < 0L) {
                deduct = Math.max(deduct, Math.min(0L, remainingAmount));
                if (deduct == 0L) continue;
            } else if (deduct == 0L) {
                continue;
            }
            if (SETTLE_PENDING.equals(record.getStatus())) {
                if (settlementRecordMapper.deductAmountIfCurrent(record.getId(), SETTLE_PENDING,
                        deduct, refundedAmount) != 1) {
                    throw new IllegalStateException("待结算记录冲正失败 recordId=" + record.getId());
                }
                record.setAmount(remainingAmount - deduct);
                record.setRefundedAmount(refundedAmount + deduct);
                if (record.getAmount() == 0L) {
                    if (settlementRecordMapper.updateStatusIfCurrent(record.getId(), SETTLE_PENDING,
                            SETTLE_CANCELED) != 1) {
                        throw new IllegalStateException("待结算记录关闭失败 recordId=" + record.getId());
                    }
                    record.setStatus(SETTLE_CANCELED);
                }
                continue;
            }
            long fullAmount = remainingAmount;
            SubjectAccount account = ensureAccount(record.getSubjectId());
            // 退款冲正顺序：可用余额 → 押金 → 都不够才抛异常（整批回滚 + 对账异常）。
            // 押金兜底让「提现后被退款」不再必然失败，是 V115 押金设计的核心用途。
            postRefundWithDepositFallback(account, record, orderId, orderNo, refundNo,
                    deduct, fullAmount, fullReverse, exact == null);
            if (fullReverse) {
                // 全退：结算记录直接置 CANCELED（与旧行为一致）
                if (settlementRecordMapper.updateStatusIfCurrent(
                        record.getId(), record.getStatus(), SETTLE_CANCELED) != 1) {
                    throw new IllegalStateException("结算记录已被并发冲正 recordId=" + record.getId());
                }
                record.setStatus(SETTLE_CANCELED);
            } else {
                // 部分退：扣减结算记录金额，保留原状态（后续可继续冲正剩余部分）
                if (settlementRecordMapper.deductAmountIfCurrent(
                        record.getId(), record.getStatus(), deduct, refundedAmount) != 1) {
                    throw new IllegalStateException("结算记录已被并发冲正 recordId=" + record.getId());
                }
                record.setAmount(fullAmount - deduct);
                record.setRefundedAmount(refundedAmount + deduct);
                if (record.getAmount() == 0L) {
                    if (settlementRecordMapper.updateStatusIfCurrent(record.getId(), record.getStatus(),
                            SETTLE_CANCELED) != 1) {
                        throw new IllegalStateException("结算记录关闭失败 recordId=" + record.getId());
                    }
                    record.setStatus(SETTLE_CANCELED);
                }
            }
        }
    }

    /**
     * 退款冲正扣款：优先扣可用余额，不足部分扣押金，仍不足才抛异常。
     *
     * <p><b>为什么需要押金兜底</b>：原实现只扣可用余额，余额不足直接抛
     * {@link InsufficientBalanceException}，外层整批回滚并记
     * {@code REFUND_BALANCE_SHORTAGE} 对账异常。经营者在提现后再发生售后退款时，
     * 退款必然失败，只能人工介入——这在实际运营中是高频问题。
     *
     * <p><b>扣款顺序</b>：可用余额 → 押金。押金是运营设定给该主体的退款保障金，
     * 优先消耗经营者自己的可用余额符合直觉，押金作为兜底。
     *
     * <p><b>两段流水的意义</b>：分开记两条 fund_flow，运营能清楚看到
     * 「这单退款有多少是从押金扣的」，避免账目混乱。
     *
     * @param account     收款主体账户（调用方已 ensureAccount）
     * @param record      结算记录
     * @param deduct      应扣金额（正数，表示从余额扣减的绝对额）
     * @param fullAmount  结算记录剩余金额（用于区分正/负分账）
     * @param fullReverse 是否整单全退
     * @param prorated    是否按比例退款（用于文案）
     */
    private void postRefundWithDepositFallback(SubjectAccount account,
                                               SettlementRecord record,
                                               Long orderId,
                                               String orderNo,
                                               String refundNo,
                                               long deduct,
                                               long fullAmount,
                                               boolean fullReverse,
                                               boolean prorated) {
        // 负分账（平台承担）与零金额场景不涉及押金兜底，保持原语义
        if (fullAmount <= 0L || deduct <= 0L) {
            post(new Posting(record.getSubjectId(),
                    account.getRoleType(),
                    fullAmount > 0L ? "REFUND" : "ADJUST",
                    -deduct,
                    BUCKET_AVAILABLE,
                    orderId,
                    orderNo,
                    record.getId(),
                    "REFUND",
                    refundNo,
                    SETTLE_SETTLED,
                    fullReverse
                            ? (fullAmount > 0L ? "退款成功，按结算记录扣回可用余额" : "退款成功，冲回平台承担的负分账")
                            : prorated ? "退款成功（部分退），按比例扣回可用余额" : "退款成功（部分退），按退货明细扣回可用余额"));
            return;
        }

        // 必须从「行锁后的实时余额」判断，不能用调用方预取的 account 快照：
        // 同一笔退款会连续处理多个结算记录，账户余额在循环中已经变化，
        // 用旧快照会把「可用余额够不够」算错（可能多扣押金或少扣可用余额）。
        SubjectAccount locked = subjectAccountMapper.selectBySubjectIdForUpdate(record.getSubjectId());
        if (locked == null) {
            locked = account;
        }
        long available = nz(locked.getAvailableBalance());
        long deposit = nz(locked.getDepositBalance());

        long fromAvailable = Math.min(Math.max(0L, available), deduct);
        long fromDeposit = deduct - fromAvailable;

        if (fromDeposit > deposit) {
            // 可用余额 + 押金 仍不足：抛异常让外层整批回滚并记对账异常（保持原语义）
            throw new InsufficientBalanceException(record.getSubjectId(),
                    available + deposit, deduct);
        }

        String suffix = fullReverse ? "" : prorated ? "（部分退按比例）" : "（部分退按明细）";
        if (fromAvailable > 0L) {
            post(new Posting(record.getSubjectId(),
                    locked.getRoleType() == null ? account.getRoleType() : locked.getRoleType(),
                    "REFUND",
                    -fromAvailable,
                    BUCKET_AVAILABLE,
                    orderId,
                    orderNo,
                    record.getId(),
                    "REFUND",
                    refundNo,
                    SETTLE_SETTLED,
                    "退款成功" + suffix + "，扣回可用余额"));
        }
        if (fromDeposit > 0L) {
            post(new Posting(record.getSubjectId(),
                    locked.getRoleType() == null ? account.getRoleType() : locked.getRoleType(),
                    "DEPOSIT_DEDUCT",
                    -fromDeposit,
                    BUCKET_DEPOSIT,
                    orderId,
                    orderNo,
                    record.getId(),
                    "REFUND",
                    refundNo,
                    SETTLE_SETTLED,
                    "退款成功" + suffix + "，可用余额不足，从押金抵扣 " + fromDeposit + " 分"));
            // 押金台账：经营者需要能看到押金减少的原因，否则会引发纠纷
            depositRecordMapper.insert(buildDepositRecord(record.getSubjectId(),
                    -fromDeposit, deposit, deposit - fromDeposit,
                    "DEDUCT_REFUND", refundNo, orderNo, null,
                    "售后退款抵扣：订单 " + orderNo + "，退款单 " + refundNo));
        }
    }

    /**
     * 构造押金台账记录。
     *
     * <p>与 {@code fund_flow} 双写：fund_flow 用于统一资金流水视图，
     * deposit_record 用于押金专项台账与运营查询（含 reason 便于解释给经营者）。
     */
    private com.wuling.finance.entity.DepositRecord buildDepositRecord(Long subjectId,
                                                                        long change,
                                                                        long before,
                                                                        long after,
                                                                        String bizType,
                                                                        String bizNo,
                                                                        String orderNo,
                                                                        String operator,
                                                                        String reason) {
        com.wuling.finance.entity.DepositRecord record = new com.wuling.finance.entity.DepositRecord();
        record.setRecordNo(nextNo("DP"));
        record.setSubjectId(subjectId);
        record.setChangeAmount(change);
        record.setBalanceBefore(before);
        record.setBalanceAfter(after);
        record.setBizType(bizType);
        record.setBizNo(bizNo);
        record.setOrderNo(orderNo);
        record.setOperator(operator);
        record.setReason(reason);
        return record;
    }

    /**
     * 运营设定/调整押金（目标值语义）。
     *
     * <p>为什么用目标值而非增量：运营的意图是「这个经营者应该有 500 元押金」，
     * 让运营自己算差额容易出错。系统按差额写台账，幂等友好。
     *
     * @param subjectId 主体
     * @param target    目标押金（分），必须 >= 0
     * @param operator  操作人
     * @param reason    原因
     * @return 变动后的押金余额
     */
    @Transactional(rollbackFor = Exception.class)
    public long setDeposit(Long subjectId, long target, String operator, String reason) {
        if (target < 0L) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "押金不能为负数");
        }
        SubjectAccount account = ensureAccount(subjectId);
        SubjectAccount locked = subjectAccountMapper.selectBySubjectIdForUpdate(subjectId);
        if (locked == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "资金账户不存在");
        }
        long before = nz(locked.getDepositBalance());
        long delta = target - before;
        if (delta == 0L) {
            return before;
        }
        String bizType = before == 0L && delta > 0L ? "SET" : "ADJUST";
        post(new Posting(subjectId,
                locked.getRoleType() == null ? account.getRoleType() : locked.getRoleType(),
                delta > 0L ? "DEPOSIT_SET" : "DEPOSIT_DEDUCT",
                delta,
                BUCKET_DEPOSIT,
                null,
                null,
                null,
                "DEPOSIT",
                nextNo("DS"),
                null,
                reason == null ? "运营调整押金" : reason));
        depositRecordMapper.insert(buildDepositRecord(subjectId, delta, before, target,
                bizType, nextNo("DS"), null, operator,
                reason == null ? "运营调整押金" : reason));
        return target;
    }

    private Map<Long, Long> mallRecordRefundShares(List<SettlementRecord> records, MallRefundShares shares) {
        Map<Long, Long> byRecord = new HashMap<>();
        Map<Long, Long> unmatched = new HashMap<>(shares.subjects());
        Long platformId = shares.commissionRefund() == 0L && shares.bonusRefund() == 0L
                ? null : platformSubjectId();
        List<SettlementRecord> platformRecords = records.stream()
                .filter(record -> platformId != null && platformId.equals(record.getSubjectId()))
                .sorted(Comparator.comparing(SettlementRecord::getId)).toList();
        if (platformId != null && platformRecords.isEmpty()) {
            throw new IllegalStateException("商城退款缺少平台结算记录");
        }
        for (SettlementRecord record : records) {
            if (platformId != null && platformId.equals(record.getSubjectId())) continue;
            Long amount = unmatched.remove(record.getSubjectId());
            if (amount != null) byRecord.put(record.getId(), amount);
        }
        if (unmatched.values().stream().anyMatch(amount -> amount != 0L)) {
            throw new IllegalStateException("商城退款缺少收款主体结算记录: " + unmatched.keySet());
        }
        if (platformRecords.size() == 1) {
            byRecord.put(platformRecords.get(0).getId(),
                    shares.commissionRefund() + shares.bonusRefund());
        } else if (platformRecords.size() == 2) {
            if (shares.commissionOriginal() <= 0L || shares.bonusOriginal() <= 0L) {
                throw new IllegalStateException("平台结算记录与原分账组成不一致");
            }
            byRecord.put(platformRecords.get(0).getId(), shares.commissionRefund());
            byRecord.put(platformRecords.get(1).getId(), shares.bonusRefund());
        } else if (platformRecords.size() > 2) {
            throw new IllegalStateException("平台结算记录数量异常");
        }
        return byRecord;
    }

    /** 后台手动冻结；业务号 MF 前缀，流水关联 MANUAL。 */
    @Transactional(rollbackFor = Exception.class)
    public void manualFreeze(Long subjectId, long amount) {
        if (amount <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "冻结金额必须大于 0");
        }
        SubjectAccount account = ensureAccount(subjectId);
        post(new Posting(subjectId,
                account.getRoleType(),
                "FREEZE",
                amount,
                BUCKET_FROZEN,
                null,
                null,
                null,
                "MANUAL",
                nextNo("MF"),
                null,
                "后台手动冻结"));
    }

    /** 后台手动解冻；业务号 MU 前缀，流水关联 MANUAL。 */
    @Transactional(rollbackFor = Exception.class)
    public void manualUnfreeze(Long subjectId, long amount) {
        if (amount <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "解冻金额必须大于 0");
        }
        SubjectAccount account = ensureAccount(subjectId);
        post(new Posting(subjectId,
                account.getRoleType(),
                "UNFREEZE",
                -amount,
                BUCKET_FROZEN,
                null,
                null,
                null,
                "MANUAL",
                nextNo("MU"),
                null,
                "后台手动解冻"));
    }

    /**
     * 统一记账入口：锁账户行、校验并更新余额、写入完整快照流水。
     *
     * <p>所有新业务流水必须经此入口，避免各业务自行拼余额导致并发前后值失真。
     */
    @Transactional(rollbackFor = Exception.class)
    public FundFlow post(Posting posting) {
        if (posting == null || posting.subjectId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "记账必须指定账户主体");
        }
        if (posting.changeAmount() == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "记账变动金额不能为 0");
        }
        if (!FLOW_TYPES.contains(posting.type())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的流水类型: " + posting.type());
        }
        validatePostingType(posting);

        ensureAccount(posting.subjectId());
        SubjectAccount account = subjectAccountMapper.selectBySubjectIdForUpdate(posting.subjectId());
        if (account == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "资金账户不存在");
        }

        long amount = Math.abs(posting.changeAmount());
        long availableBefore = nz(account.getAvailableBalance());
        long frozenBefore = nz(account.getFrozenBalance());
        long depositBefore = nz(account.getDepositBalance());
        long availableDelta = 0L;
        long frozenDelta = 0L;
        long depositDelta = 0L;
        long snapshotBefore;
        long snapshotChange;

        switch (posting.type()) {
            case "INCOME", "REFUND", "ADJUST" -> {
                availableDelta = posting.changeAmount();
                snapshotBefore = availableBefore;
                snapshotChange = posting.changeAmount();
            }
            case "FREEZE" -> {
                availableDelta = -amount;
                frozenDelta = amount;
                snapshotBefore = frozenBefore;
                snapshotChange = amount;
            }
            case "UNFREEZE", "WITHDRAW" -> {
                availableDelta = "UNFREEZE".equals(posting.type()) ? amount : 0L;
                frozenDelta = -amount;
                snapshotBefore = frozenBefore;
                snapshotChange = -amount;
            }
            case "DEPOSIT_SET", "DEPOSIT_DEDUCT" -> {
                // 押金桶独立增减：正数加押金，负数扣押金；不触碰 available / frozen
                depositDelta = posting.changeAmount();
                snapshotBefore = depositBefore;
                snapshotChange = posting.changeAmount();
            }
            default -> throw new BusinessException(ResultCode.BAD_REQUEST,
                    "不支持的流水类型: " + posting.type());
        }

        long availableAfter = availableBefore + availableDelta;
        long frozenAfter = frozenBefore + frozenDelta;
        long depositAfter = depositBefore + depositDelta;
        if (availableAfter < 0) {
            throw new InsufficientBalanceException(posting.subjectId(), availableBefore, -availableDelta);
        }
        if (frozenAfter < 0) {
            throw new InsufficientBalanceException(posting.subjectId(), frozenBefore, -frozenDelta);
        }
        if (depositAfter < 0) {
            throw new InsufficientBalanceException(posting.subjectId(), depositBefore, -depositDelta);
        }

        account.setAvailableBalance(availableAfter);
        account.setFrozenBalance(frozenAfter);
        account.setDepositBalance(depositAfter);
        if ("INCOME".equals(posting.type()) && posting.changeAmount() > 0) {
            account.setTotalIncome(nz(account.getTotalIncome()) + posting.changeAmount());
        }
        if ("WITHDRAW".equals(posting.type())) {
            account.setTotalWithdrawn(nz(account.getTotalWithdrawn()) + amount);
        }
        if (subjectAccountMapper.updateById(account) == 0) {
            throw new IllegalStateException("资金账户更新失败 subjectId=" + posting.subjectId());
        }

        long snapshotAfter = snapshotBefore + snapshotChange;
        FundFlow flow = new FundFlow();
        flow.setFlowNo(nextNo("FF"));
        flow.setSubjectId(posting.subjectId());
        flow.setRoleType(posting.roleType() == null ? account.getRoleType() : posting.roleType());
        flow.setType(posting.type());
        flow.setDirection(snapshotChange >= 0 ? "in" : "out");
        flow.setAmount(amount);
        flow.setAccountId(account.getId());
        flow.setOrderId(posting.orderId());
        flow.setOrderNo(posting.orderNo());
        flow.setSettlementRecordId(posting.settlementRecordId());
        flow.setBizType(posting.bizType());
        flow.setBizNo(posting.bizNo());
        flow.setBalanceBucket(posting.balanceBucket());
        flow.setBalanceBefore(snapshotBefore);
        flow.setChangeAmount(snapshotChange);
        flow.setBalanceAfter(snapshotAfter);
        flow.setSettlementStatus(posting.settlementStatus());
        flow.setRemark(posting.remark());
        fundFlowMapper.insert(flow);
        return flow;
    }

    private void validatePostingType(Posting posting) {
        String type = posting.type();
        String bucket = posting.balanceBucket();
        long change = posting.changeAmount();
        if (("INCOME".equals(type) || "REFUND".equals(type) || "ADJUST".equals(type))
                && !BUCKET_AVAILABLE.equals(bucket)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, type + " 必须作用于可用余额");
        }
        if (("FREEZE".equals(type) || "UNFREEZE".equals(type) || "WITHDRAW".equals(type))
                && !BUCKET_FROZEN.equals(bucket)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, type + " 必须作用于冻结余额");
        }
        if (("INCOME".equals(type) || "FREEZE".equals(type)) && change <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, type + " 变动金额必须为正");
        }
        if (("REFUND".equals(type) || "UNFREEZE".equals(type) || "WITHDRAW".equals(type)) && change >= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, type + " 变动金额必须为负");
        }
        // 押金桶：只允许 DEPOSIT_SET / DEPOSIT_DEDUCT 两种类型触达。
        // 这是「押金不可提现」的第三重保障——WITHDRAW 被上面强制绑定 FROZEN 桶，
        // 押金桶在结构上就触达不到提现路径。
        if ("DEPOSIT_SET".equals(type)) {
            if (!BUCKET_DEPOSIT.equals(bucket)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "DEPOSIT_SET 必须作用于押金桶");
            }
            if (change <= 0) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "DEPOSIT_SET 变动金额必须为正");
            }
        }
        if ("DEPOSIT_DEDUCT".equals(type)) {
            if (!BUCKET_DEPOSIT.equals(bucket)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "DEPOSIT_DEDUCT 必须作用于押金桶");
            }
            if (change >= 0) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "DEPOSIT_DEDUCT 变动金额必须为负");
            }
        }
    }
    /**
     * 获取或创建主体资金账户。
     *
     * 并发安全（第 0 期加固）：并发首次开户时，两线程可能同时通过「查不到」判断。
     * 依赖 subject_account 唯一索引 uk_subject_account_subject(subject_id)，
     * 后到者在 insert 时抛 DuplicateKeyException，此处捕获后重新查询返回，
     * 避免开户失败或产生重复账户。
     */
    public SubjectAccount ensureAccount(Long subjectId) {
        SubjectAccount account = subjectAccountMapper.selectOne(new LambdaQueryWrapper<SubjectAccount>()
                .eq(SubjectAccount::getSubjectId, subjectId));
        if (account != null) {
            return account;
        }
        SubjectQueryPort.SubjectView subject = subjectQueryPort.findById(subjectId);
        account = new SubjectAccount();
        account.setSubjectId(subjectId);
        account.setRoleType(subject == null ? "UNKNOWN" : subject.getSubjectType());
        account.setAvailableBalance(0L);
        account.setFrozenBalance(0L);
        account.setTotalIncome(0L);
        account.setTotalWithdrawn(0L);
        account.setVersion(0);
        try {
            subjectAccountMapper.insert(account);
        } catch (DuplicateKeyException e) {
            // 并发开户竞争失败：改为读取已存在账户
            SubjectAccount existing = subjectAccountMapper.selectOne(
                    new LambdaQueryWrapper<SubjectAccount>().eq(SubjectAccount::getSubjectId, subjectId));
            if (existing != null) {
                return existing;
            }
            throw e;
        }
        return account;
    }

    public List<SubjectAccount> accountsOf(List<Long> subjectIds) {
        if (subjectIds == null || subjectIds.isEmpty()) {
            return List.of();
        }
        return subjectAccountMapper.selectList(new LambdaQueryWrapper<SubjectAccount>()
                .in(SubjectAccount::getSubjectId, subjectIds));
    }

    private Long platformSubjectId() {
        return subjectQueryPort.findFirstByType("PLATFORM");
    }

    private Long investorOf(Long storeSubjectId) {
        if (storeSubjectId == null) {
            return null;
        }
        return subjectQueryPort.findInvestorOfStore(storeSubjectId);
    }

    /**
     * 投资人当月累计已分账金额（分）。
     *
     * <p>取当月自然月区间 [本月1日 00:00, 下月1日 00:00)，跨月自动归零。
     * 未绑定投资人时返回 0，表示不启用阈值判定（SplitCalculator 会退回原比例）。
     *
     * @param investorSubjectId 投资人主体 id，可为 null
     * @return 当月累计分账额（分），无投资人时为 0
     */
    private Long accumulatedThisMonth(Long investorSubjectId) {
        if (investorSubjectId == null) {
            return 0L;
        }
        LocalDate today = LocalDate.now();
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();
        LocalDateTime monthEnd = monthStart.plusMonths(1);
        Long sum = settlementRecordMapper.selectSumCurrentMonth(investorSubjectId, monthStart, monthEnd);
        return sum == null ? 0L : sum;
    }


    private Long selectOrderId(String orderNo) {
        return splitSnapshotMapper.selectOrderIdByNo(orderNo);
    }

    private void putIfNonZero(Map<Long, Long> map, Long subjectId, long amount) {
        if (subjectId != null && amount != 0L) {
            map.merge(subjectId, amount, Long::sum);
        }
    }

    private long nz(Long value) {
        return value == null ? 0L : value;
    }

    private String nextNo(String prefix) {
        return prefix + System.currentTimeMillis() % 100000000L
                + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
    }
}
