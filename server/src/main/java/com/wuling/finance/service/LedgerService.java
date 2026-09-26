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
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
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

    private static final Set<String> FLOW_TYPES = Set.of(
            "INCOME", "WITHDRAW", "REFUND", "FREEZE", "UNFREEZE");
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

    public LedgerService(SplitRuleMapper splitRuleMapper,
                         SplitSnapshotMapper splitSnapshotMapper,
                         SubjectAccountMapper subjectAccountMapper,
                         FundFlowMapper fundFlowMapper,
                         SettlementRecordMapper settlementRecordMapper,
                         ReconcileService reconcileService,
                         SubjectQueryPort subjectQueryPort,
                         SplitCalculator splitCalculator,
                         PlatformTransactionManager transactionManager) {
        this.splitRuleMapper = splitRuleMapper;
        this.splitSnapshotMapper = splitSnapshotMapper;
        this.subjectAccountMapper = subjectAccountMapper;
        this.fundFlowMapper = fundFlowMapper;
        this.settlementRecordMapper = settlementRecordMapper;
        this.reconcileService = reconcileService;
        this.subjectQueryPort = subjectQueryPort;
        this.splitCalculator = splitCalculator;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
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

        SplitRule rule = resolveRule(productId);
        if (rule == null) {
            throw new IllegalStateException("未配置分账规则，无法分账: " + orderNo);
        }
        boolean hasChannel = channelSubjectId != null;
        // 投资人「当月达标后比例」：需先算出该投资人当月已累计分账额，
        // 再交给 SplitCalculator 决定本单用原比例还是达标后比例。
        // 未绑定投资人（investorOf 为 null）时不启用阈值判定。
        Long investorSubjectId = investorOf(storeSubjectId);
        Long accumulatedInvestorAmount = accumulatedThisMonth(investorSubjectId);
        SplitCalculator.SplitAmount amount = splitCalculator.calc(
                paidAmount, itemCount, hasChannel, rule, platformCommission,
                items == null ? List.of() : items, accumulatedInvestorAmount);

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
        snapshot.setPlatformCommission(amount.platformCommission());
        snapshot.setPlatformBonus(amount.platformBonus());
        long sum = amount.platform() + amount.store() + amount.channel() + amount.investor() + amount.supplier();
        snapshot.setTotalCheck(sum == paidAmount ? "一致" : "不一致");
        snapshot.setStatus(sum == paidAmount ? "valid" : "invalid");
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

        // 各方待结算台账（按明细汇总，供应商可多主体）
        Map<Long, Long> credits = new LinkedHashMap<>();
        Long platformSubjectId = platformSubjectId();
        putIfPositive(credits, platformSubjectId, amount.platform());
        putIfPositive(credits, storeSubjectId, amount.store());
        putIfPositive(credits, channelSubjectId, amount.channel());
        putIfPositive(credits, investorSubjectId, amount.investor());

        // 供应商按明细分别归集到各自主体（成本单价 × 件数，与快照 supplierAmount 同口径）
        if (items != null) {
            for (SplitCalculator.LineItem item : items) {
                if (item == null || item.supplierSubjectId() == null) {
                    continue;
                }
                long unitCost = item.costPrice() == null ? 0L : item.costPrice();
                Integer qty = item.quantity();
                long share = unitCost * (qty == null || qty <= 0 ? 1L : qty);
                putIfPositive(credits, item.supplierSubjectId(), share);
            }
        }

        List<Map.Entry<Long, Long>> orderedCredits = credits.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .toList();
        for (Map.Entry<Long, Long> entry : orderedCredits) {
            recordSettled(snapshot, entry.getKey(), entry.getValue());
        }
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
        SubjectQueryPort.SubjectView subject = subjectQueryPort.findById(subjectId);
        SettlementRecord record = new SettlementRecord();
        record.setRecordNo(nextNo("SR"));
        record.setSubjectId(subjectId);
        record.setSnapshotId(snapshot.getId());
        record.setOrderId(snapshot.getOrderId());
        record.setAmount(amount);
        record.setStatus(SETTLE_SETTLED);
        record.setSettleDate(LocalDate.now());
        settlementRecordMapper.insert(record);

        post(new Posting(subjectId,
                subject == null ? null : subject.getSubjectType(),
                "INCOME",
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
        Long orderId = selectOrderId(orderNo);
        if (orderId == null) {
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> reverseInTransaction(orderId, orderNo, refundNo));
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

    /** 兼容旧调用；新退款链路应携带 refundNo。 */
    public void reverseForOrder(String orderNo) {
        reverseForOrder(orderNo, null);
    }

    private void reverseInTransaction(Long orderId, String orderNo, String refundNo) {
        List<SettlementRecord> records = settlementRecordMapper.selectList(new LambdaQueryWrapper<SettlementRecord>()
                .eq(SettlementRecord::getOrderId, orderId)
                .orderByAsc(SettlementRecord::getSubjectId, SettlementRecord::getId));
        for (SettlementRecord record : records) {
            if (SETTLE_CANCELED.equals(record.getStatus())) {
                continue;
            }
            if (SETTLE_PENDING.equals(record.getStatus())) {
                // 历史待结算尚未进入任何余额桶，只取消，不伪造退款流水。
                record.setStatus(SETTLE_CANCELED);
                settlementRecordMapper.updateById(record);
                continue;
            }
            if (!FUNDED_SETTLEMENT_STATUSES.contains(record.getStatus())) {
                throw new IllegalStateException("未知结算状态，无法退款冲正: " + record.getStatus());
            }

            SubjectAccount account = ensureAccount(record.getSubjectId());
            post(new Posting(record.getSubjectId(),
                    account.getRoleType(),
                    "REFUND",
                    -Math.abs(record.getAmount()),
                    BUCKET_AVAILABLE,
                    orderId,
                    orderNo,
                    record.getId(),
                    "REFUND",
                    refundNo,
                    SETTLE_SETTLED,
                    "退款成功，按结算记录扣回可用余额"));
            record.setStatus(SETTLE_CANCELED);
            settlementRecordMapper.updateById(record);
        }
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
        long availableDelta = 0L;
        long frozenDelta = 0L;
        long snapshotBefore;
        long snapshotChange;

        switch (posting.type()) {
            case "INCOME", "REFUND" -> {
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
            default -> throw new BusinessException(ResultCode.BAD_REQUEST,
                    "不支持的流水类型: " + posting.type());
        }

        long availableAfter = availableBefore + availableDelta;
        long frozenAfter = frozenBefore + frozenDelta;
        if (availableAfter < 0) {
            throw new InsufficientBalanceException(posting.subjectId(), availableBefore, -availableDelta);
        }
        if (frozenAfter < 0) {
            throw new InsufficientBalanceException(posting.subjectId(), frozenBefore, -frozenDelta);
        }

        account.setAvailableBalance(availableAfter);
        account.setFrozenBalance(frozenAfter);
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
        if (("INCOME".equals(type) || "REFUND".equals(type)) && !BUCKET_AVAILABLE.equals(bucket)) {
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

    private void putIfPositive(Map<Long, Long> map, Long subjectId, long amount) {
        if (subjectId != null && amount > 0) {
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





