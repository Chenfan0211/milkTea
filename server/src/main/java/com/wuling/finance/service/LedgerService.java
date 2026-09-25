package com.wuling.finance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.finance.entity.FundFlow;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 分账与台账记账。
 * 规则：
 * - 核销后生成不可变五方快照，收益先记「待结算」；
 * - T+1 由待结算转「可结算」（可用余额）；
 * - 退款同步冲正，已结算不可退。
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    public static final String SETTLE_PENDING = "PENDING";
    public static final String SETTLE_SETTLEABLE = "SETTLEABLE";
    public static final String SETTLE_SETTLED = "SETTLED";

    private final SplitRuleMapper splitRuleMapper;
    private final SplitSnapshotMapper splitSnapshotMapper;
    private final SubjectAccountMapper subjectAccountMapper;
    private final FundFlowMapper fundFlowMapper;
    private final SettlementRecordMapper settlementRecordMapper;
    /** 第 12 期：主体查询改走端口（本地实现，无网络开销） */
    private final SubjectQueryPort subjectQueryPort;
    private final SplitCalculator splitCalculator;

    public LedgerService(SplitRuleMapper splitRuleMapper,
                         SplitSnapshotMapper splitSnapshotMapper,
                         SubjectAccountMapper subjectAccountMapper,
                         FundFlowMapper fundFlowMapper,
                         SettlementRecordMapper settlementRecordMapper,
                         SubjectQueryPort subjectQueryPort,
                         SplitCalculator splitCalculator) {
        this.splitRuleMapper = splitRuleMapper;
        this.splitSnapshotMapper = splitSnapshotMapper;
        this.subjectAccountMapper = subjectAccountMapper;
        this.fundFlowMapper = fundFlowMapper;
        this.settlementRecordMapper = settlementRecordMapper;
        this.subjectQueryPort = subjectQueryPort;
        this.splitCalculator = splitCalculator;
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
     * 订单核销后分账：写不可变快照 + 各方待结算台账。
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

        // 供应商按明细分别归集到各自主体
        if (items != null) {
            for (SplitCalculator.LineItem item : items) {
                if (item == null || item.supplierSubjectId() == null) {
                    continue;
                }
                long share = item.amount() * rule.getSupplierRatio() / 10000;
                putIfPositive(credits, item.supplierSubjectId(), share);
            }
        }

        for (Map.Entry<Long, Long> entry : credits.entrySet()) {
            recordPending(snapshot, entry.getKey(), entry.getValue());
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
    /** 记待结算台账（不动可用余额） */
    private void recordPending(SplitSnapshot snapshot, Long subjectId, long amount) {
        SubjectQueryPort.SubjectView subject = subjectQueryPort.findById(subjectId);
        SettlementRecord record = new SettlementRecord();
        record.setRecordNo(nextNo("SR"));
        record.setSubjectId(subjectId);
        record.setSnapshotId(snapshot.getId());
        record.setOrderId(snapshot.getOrderId());
        record.setAmount(amount);
        record.setStatus(SETTLE_PENDING);
        settlementRecordMapper.insert(record);

        writeFlow(subjectId, subject == null ? null : subject.getSubjectType(), "INCOME", "in",
                amount, snapshot.getOrderNo(), balanceOf(subjectId), "订单分账入账（待结算）");
    }

    /** T+1 结算：待结算 -> 可结算（计入可用余额） */
    @Transactional(rollbackFor = Exception.class)
    public int settleDue(LocalDate settleDate) {
        List<SettlementRecord> pendings = settlementRecordMapper.selectList(new LambdaQueryWrapper<SettlementRecord>()
                .eq(SettlementRecord::getStatus, SETTLE_PENDING)
                .le(SettlementRecord::getCreateTime, settleDate.plusDays(1).atStartOfDay())
                .orderByAsc(SettlementRecord::getId));
        int count = 0;
        for (SettlementRecord record : pendings) {
            SubjectAccount account = ensureAccount(record.getSubjectId());
            // 并发安全（第 0 期加固）：原子入账，避免与提现/分账并发时丢失更新
            subjectAccountMapper.creditSettle(record.getSubjectId(), record.getAmount());

            record.setStatus(SETTLE_SETTLEABLE);
            record.setSettleDate(settleDate);
            settlementRecordMapper.updateById(record);

            long balanceAfter = (account.getAvailableBalance() == null ? 0L : account.getAvailableBalance())
                    + record.getAmount();
            writeFlow(record.getSubjectId(), account.getRoleType(), "SETTLE", "in",
                    record.getAmount(), null, balanceAfter, "T+1 结算转为可结算");
            count++;
        }
        return count;
    }

    /**
     * 退款冲正：仅「待结算」订单可冲正（钱尚未进入可用余额）。
     * 已进入可结算（SETTLEABLE）或已结算（SETTLED）的订单一律拒绝，避免资金穿透。
     */
    @Transactional(rollbackFor = Exception.class)
    public void reverseForOrder(String orderNo) {
        Long orderId = selectOrderId(orderNo);
        if (orderId == null) {
            return;
        }
        List<SettlementRecord> records = settlementRecordMapper.selectList(new LambdaQueryWrapper<SettlementRecord>()
                .eq(SettlementRecord::getOrderId, orderId));
        for (SettlementRecord record : records) {
            if (SETTLE_SETTLED.equals(record.getStatus())) {
                throw new IllegalStateException("订单已结算，不可退款: " + orderNo);
            }
            if (SETTLE_SETTLEABLE.equals(record.getStatus())) {
                throw new IllegalStateException("订单已进入可结算，不可退款: " + orderNo);
            }
            writeFlow(record.getSubjectId(), null, "REFUND", "out",
                    record.getAmount(), orderNo, balanceOf(record.getSubjectId()), "退款冲正（待结算取消）");
            record.setStatus("CANCELED");
            settlementRecordMapper.updateById(record);
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

    private void writeFlow(Long subjectId, String roleType, String type, String direction,
                           long amount, String orderNo, long balanceAfter, String remark) {
        FundFlow flow = new FundFlow();
        flow.setFlowNo(nextNo("FF"));
        flow.setSubjectId(subjectId);
        flow.setRoleType(roleType);
        flow.setType(type);
        flow.setDirection(direction);
        flow.setAmount(amount);
        flow.setOrderNo(orderNo);
        flow.setBalanceAfter(balanceAfter);
        flow.setRemark(remark);
        fundFlowMapper.insert(flow);
    }

    private long balanceOf(Long subjectId) {
        SubjectAccount account = subjectAccountMapper.selectOne(new LambdaQueryWrapper<SubjectAccount>()
                .eq(SubjectAccount::getSubjectId, subjectId));
        return account == null ? 0L : account.getAvailableBalance();
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

    private String nextNo(String prefix) {
        return prefix + System.currentTimeMillis() % 100000000L
                + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
    }
}





