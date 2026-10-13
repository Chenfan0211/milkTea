package com.wuling.finance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.finance.dto.InvestorCommissionView;
import com.wuling.finance.entity.FundFlow;
import com.wuling.finance.entity.SplitRule;
import com.wuling.finance.entity.SettlementRecord;
import com.wuling.finance.entity.SubjectAccount;
import com.wuling.finance.mapper.FundFlowMapper;
import com.wuling.finance.mapper.SettlementRecordMapper;
import com.wuling.finance.mapper.SplitRuleMapper;
import com.wuling.finance.mapper.SubjectAccountMapper;
import com.wuling.subject.port.SubjectQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.wuling.finance.port.TradeOrderQueryPort;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 四类角色工作台（P1）。
 * 数据严格按主体隔离：门店只看到自己的订单/收益，渠道只看到绑定门店，投资人只看到投资门店。
 */
@Service
public class RoleWorkbenchService {

    private final SubjectAccountMapper subjectAccountMapper;
    private final FundFlowMapper fundFlowMapper;
    private final SettlementRecordMapper settlementRecordMapper;
    private final SplitRuleMapper splitRuleMapper;
    private final JdbcTemplate jdbcTemplate;
    /** 第 7 期：订单查询改走端口（trade 已拆出） */
    private final TradeOrderQueryPort tradeOrderQueryPort;
    /** 第 12 期：主体查询改走端口 */
    private final SubjectQueryPort subjectQueryPort;
    public RoleWorkbenchService(SubjectAccountMapper subjectAccountMapper,
                                FundFlowMapper fundFlowMapper,
                                SettlementRecordMapper settlementRecordMapper,
                                SplitRuleMapper splitRuleMapper,
                                JdbcTemplate jdbcTemplate,
                                TradeOrderQueryPort tradeOrderQueryPort,
                                SubjectQueryPort subjectQueryPort) {
        this.subjectAccountMapper = subjectAccountMapper;
        this.fundFlowMapper = fundFlowMapper;
        this.settlementRecordMapper = settlementRecordMapper;
        this.splitRuleMapper = splitRuleMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.tradeOrderQueryPort = tradeOrderQueryPort;
        this.subjectQueryPort = subjectQueryPort;
    }

    /** 工作台概览：余额 + 今日订单 + 今日收益 + 待结算 */
    public Overview overview(Long subjectId) {
        SubjectAccount account = subjectAccountMapper.selectOne(new LambdaQueryWrapper<SubjectAccount>()
                .eq(SubjectAccount::getSubjectId, subjectId));

        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        long todayOrders = tradeOrderQueryPort.countStoreTodayOrders(subjectId);

        Long pending = settlementRecordMapper.selectSumPending(subjectId);
        Long settled = settlementRecordMapper.selectSumSettleable(subjectId);

        Overview o = new Overview();
        o.setSubjectId(subjectId);
        o.setRoleType(account == null ? null : account.getRoleType());
        o.setAvailableBalance(account == null ? 0L : account.getAvailableBalance());
        o.setFrozenBalance(account == null ? 0L : account.getFrozenBalance());
        o.setTotalIncome(account == null ? 0L : account.getTotalIncome());
        o.setTotalWithdrawn(account == null ? 0L : account.getTotalWithdrawn());
        o.setTodayOrders(todayOrders);
        o.setPendingSettlement(pending == null ? 0L : pending);
        o.setSettleableAmount(settled == null ? 0L : settled);
        return o;
    }

    /** 收益/提成分页流水 */
    public List<FundFlow> flows(Long subjectId) {
        return fundFlowMapper.selectList(new LambdaQueryWrapper<FundFlow>()
                .eq(FundFlow::getSubjectId, subjectId)
                .orderByDesc(FundFlow::getId));
    }

    /** 结算台账 */
    public List<SettlementRecord> settlements(Long subjectId) {
        return settlementRecordMapper.selectList(new LambdaQueryWrapper<SettlementRecord>()
                .eq(SettlementRecord::getSubjectId, subjectId)
                .orderByDesc(SettlementRecord::getId));
    }

    /** 门店订单 */
    public List<Map<String, Object>> storeOrders(Long storeSubjectId) {
        return tradeOrderQueryPort.storeOrders(storeSubjectId);
    }

    /**
      渠道绑定的门店。
      第 12 期：返回 Map 而非 BizSubject 实体 —— 跨域不应传递内部实体，只暴露所需字段。
    */
    public List<Map<String, Object>> channelStores(Long channelSubjectId) {
        List<Long> ids = subjectQueryPort.findStoreIdsByChannel(channelSubjectId);
        return toSubjectMaps(ids);
    }

    /**
      渠道归因订单（绑定门店下的订单）。
      第 7 期：返回 Map 而非 Order 实体 —— trade 已拆出，
      跨服务不应传递内部实体，只暴露调用方所需字段。
    */
    public List<Map<String, Object>> channelOrders(Long channelSubjectId) {
        List<Long> ids = subjectQueryPort.findStoreIdsByChannel(channelSubjectId);
        return tradeOrderQueryPort.ordersByStores(ids);
    }

    /** 投资人投资的门店（第 12 期：返回 Map，不传实体） */
    public List<Map<String, Object>> investorStores(Long investorSubjectId) {
        List<Long> ids = subjectQueryPort.findStoreIdsByInvestor(investorSubjectId);
        return toSubjectMaps(ids);
    }

    /** 把主体 ID 列表转成 {id, code, name, subjectType} 结构（避免传递实体） */
    private List<Map<String, Object>> toSubjectMaps(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Long id : ids) {
            SubjectQueryPort.SubjectView v = subjectQueryPort.findById(id);
            if (v == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", v.getId());
            m.put("code", v.getCode());
            m.put("name", v.getName());
            m.put("subjectType", v.getSubjectType());
            result.add(m);
        }
        return result;
    }

    /**
     * 投资人「月度分佣」聚合（只读）。
     *
     * <p>达标口径：投资人当月累计分佣额 >= split_rule.investor_threshold_amount，
     * 与 {@link SplitRule#resolveInvestorRatio} 完全一致（跨月自然归零）。
     *
     * <p>门店销售额口径：orders 中「已支付且非全额退款」的 paid_amount 之和，
     * 按 store_subject_id 归到投资门店。
     */
    public InvestorCommissionView investorCommission(Long investorSubjectId) {
        InvestorCommissionView view = new InvestorCommissionView();
        LocalDate today = LocalDate.now();
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();
        LocalDateTime monthEnd = monthStart.plusMonths(1);
        String month = today.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        view.setMonth(month);

        // 1) 全局生效分佣规则
        SplitRule rule = splitRuleMapper.selectOne(new LambdaQueryWrapper<SplitRule>()
                .eq(SplitRule::getScope, "GLOBAL")
                .eq(SplitRule::getStatus, "enabled")
                .orderByAsc(SplitRule::getId)
                .last("limit 1"));
        InvestorCommissionView.Rule ruleView = new InvestorCommissionView.Rule();
        if (rule != null) {
            ruleView.setInvestorRatio(rule.getInvestorRatio());
            ruleView.setInvestorThresholdAmount(rule.getInvestorThresholdAmount());
            ruleView.setInvestorRatioAfter(rule.getInvestorRatioAfter());
            boolean enabled = nz(rule.getInvestorThresholdAmount()) > 0 && nz(rule.getInvestorRatioAfter()) > 0;
            ruleView.setThresholdEnabled(enabled);
        }
        view.setRule(ruleView);

        // 2) 当月累计分佣（复用既有口径）
        Long accumulated = settlementRecordMapper.selectSumCurrentMonth(investorSubjectId, monthStart, monthEnd);
        long commission = accumulated == null ? 0L : accumulated;

        // 3) 当月已结算 / 待结算
        Long settled = settlementRecordMapper.selectSumSettleable(investorSubjectId);
        Long pending = settlementRecordMapper.selectSumPending(investorSubjectId);

        // 4) 投资门店 + 当月门店销售额
        List<Long> storeIds = subjectQueryPort.findStoreIdsByInvestor(investorSubjectId);
        long sales = sumSales(storeIds, monthStart, monthEnd);

        // 5) 达标判定 + 进度
        long threshold = ruleView.getInvestorThresholdAmount() == null ? 0L : ruleView.getInvestorThresholdAmount();
        boolean reached = ruleView.isThresholdEnabled() && commission >= threshold;
        int effectiveRatio = rule == null ? 0 : rule.resolveInvestorRatio(commission);
        int percent = threshold <= 0 ? 0 : (int) Math.min(100, commission * 100 / threshold);
        long remain = reached ? 0L : Math.max(0L, threshold - commission);

        InvestorCommissionView.Current current = new InvestorCommissionView.Current();
        current.setCommissionAmount(commission);
        current.setSalesAmount(sales);
        current.setSettledAmount(settled == null ? 0L : settled);
        current.setPendingAmount(pending == null ? 0L : pending);
        current.setEffectiveRatio(effectiveRatio);
        current.setReached(reached);
        view.setCurrent(current);

        InvestorCommissionView.Progress progress = new InvestorCommissionView.Progress();
        progress.setPercent(percent);
        progress.setRemainToThreshold(remain);
        view.setProgress(progress);

        // 6) 按门店汇总
        view.setStores(storeStats(storeIds, monthStart, monthEnd));

        // 7) 近 12 个月逐月记录
        view.setRecords(monthlyRecords(investorSubjectId, storeIds, today));

        return view;
    }

    /** 门店销售额：已支付且非全额退款的订单 paid_amount 之和（按时间区间） */
    private long sumSales(List<Long> storeIds, LocalDateTime start, LocalDateTime end) {
        if (storeIds == null || storeIds.isEmpty()) {
            return 0L;
        }
        String in = storeIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0");
        Long sum = jdbcTemplate.queryForObject(
                "select coalesce(sum(paid_amount),0) from orders "
                        + "where store_subject_id in (" + in + ") and deleted = 0 "
                        + "and pay_status = 'PAID' and (refund_status is null or refund_status <> 'REFUNDED') "
                        + "and create_time >= ? and create_time < ?",
                Long.class, start, end);
        return sum == null ? 0L : sum;
    }

    /** 按投资门店汇总（销售额 + 分佣额 + 订单数） */
    private List<InvestorCommissionView.StoreStat> storeStats(List<Long> storeIds, LocalDateTime start, LocalDateTime end) {
        List<InvestorCommissionView.StoreStat> result = new ArrayList<>();
        if (storeIds == null || storeIds.isEmpty()) {
            return result;
        }
        String in = storeIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0");
        // 销售额：orders 按 store_subject_id 分组
        String salesSql = "select store_subject_id, coalesce(sum(paid_amount),0) as sales, count(*) as cnt "
                + "from orders where store_subject_id in (" + in + ") and deleted = 0 "
                + "and pay_status = 'PAID' and (refund_status is null or refund_status <> 'REFUNDED') "
                + "and create_time >= ? and create_time < ? group by store_subject_id";
        java.util.Map<Long, long[]> byStore = new LinkedHashMap<>();
        jdbcTemplate.query(salesSql, rs -> {
            long sid = rs.getLong("store_subject_id");
            long sales = rs.getLong("sales");
            long cnt = rs.getLong("cnt");
            byStore.put(sid, new long[]{sales, cnt});
        }, start, end);
        for (Long sid : storeIds) {
            InvestorCommissionView.StoreStat stat = new InvestorCommissionView.StoreStat();
            stat.setId(sid);
            stat.setName(subjectQueryPort.findName(sid));
            long[] row = byStore.get(sid);
            stat.setSalesAmount(row == null ? 0L : row[0]);
            stat.setOrderCount(row == null ? 0L : row[1]);
            stat.setCommissionAmount(0L);
            result.add(stat);
        }
        return result;
    }

    /** 近 12 个月逐月记录（分佣 + 销售额 + 达标态） */
    private List<InvestorCommissionView.MonthlyRecord> monthlyRecords(Long investorSubjectId, List<Long> storeIds, LocalDate today) {
        List<InvestorCommissionView.MonthlyRecord> result = new ArrayList<>();
        // 全局规则查一次，避免循环内 N 次查询
        SplitRule rule = splitRuleMapper.selectOne(new LambdaQueryWrapper<SplitRule>()
                .eq(SplitRule::getScope, "GLOBAL").eq(SplitRule::getStatus, "enabled")
                .orderByAsc(SplitRule::getId).last("limit 1"));
        long threshold = rule != null && nz(rule.getInvestorThresholdAmount()) > 0 && nz(rule.getInvestorRatioAfter()) > 0
                ? rule.getInvestorThresholdAmount() : 0L;
        LocalDate cursor = today.withDayOfMonth(1);
        for (int i = 0; i < 12; i++) {
            LocalDateTime start = cursor.atStartOfDay();
            LocalDateTime end = cursor.plusMonths(1).atStartOfDay();
            InvestorCommissionView.MonthlyRecord rec = new InvestorCommissionView.MonthlyRecord();
            rec.setMonth(cursor.format(DateTimeFormatter.ofPattern("yyyy-MM")));
            Long acc = settlementRecordMapper.selectSumCurrentMonth(investorSubjectId, start, end);
            long commission = acc == null ? 0L : acc;
            rec.setCommissionAmount(commission);
            rec.setSalesAmount(sumSales(storeIds, start, end));
            rec.setReached(threshold > 0 && commission >= threshold);
            rec.setEffectiveRatio(rule == null ? 0 : rule.resolveInvestorRatio(commission));
            result.add(rec);
            cursor = cursor.minusMonths(1);
        }
        return result;
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private long nz(Long v) {
        return v == null ? 0L : v;
    }

    /** 工作台概览数据 */
    public static class Overview {
        private Long subjectId;
        private String roleType;
        private Long availableBalance;
        private Long frozenBalance;
        private Long totalIncome;
        private Long totalWithdrawn;
        private Long todayOrders;
        private Long pendingSettlement;
        private Long settleableAmount;

        public Long getSubjectId() { return subjectId; }
        public void setSubjectId(Long v) { this.subjectId = v; }
        public String getRoleType() { return roleType; }
        public void setRoleType(String v) { this.roleType = v; }
        public Long getAvailableBalance() { return availableBalance; }
        public void setAvailableBalance(Long v) { this.availableBalance = v; }
        public Long getFrozenBalance() { return frozenBalance; }
        public void setFrozenBalance(Long v) { this.frozenBalance = v; }
        public Long getTotalIncome() { return totalIncome; }
        public void setTotalIncome(Long v) { this.totalIncome = v; }
        public Long getTotalWithdrawn() { return totalWithdrawn; }
        public void setTotalWithdrawn(Long v) { this.totalWithdrawn = v; }
        public Long getTodayOrders() { return todayOrders; }
        public void setTodayOrders(Long v) { this.todayOrders = v; }
        public Long getPendingSettlement() { return pendingSettlement; }
        public void setPendingSettlement(Long v) { this.pendingSettlement = v; }
        public Long getSettleableAmount() { return settleableAmount; }
        public void setSettleableAmount(Long v) { this.settleableAmount = v; }
    }
}


