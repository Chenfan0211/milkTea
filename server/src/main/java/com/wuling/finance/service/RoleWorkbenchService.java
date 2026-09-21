package com.wuling.finance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.finance.entity.FundFlow;
import com.wuling.finance.entity.SettlementRecord;
import com.wuling.finance.entity.SubjectAccount;
import com.wuling.finance.mapper.FundFlowMapper;
import com.wuling.finance.mapper.SettlementRecordMapper;
import com.wuling.finance.mapper.SubjectAccountMapper;
import com.wuling.subject.entity.BizSubject;
import com.wuling.subject.mapper.BizSubjectMapper;
import com.wuling.trade.entity.Order;
import com.wuling.trade.mapper.OrderMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 四类角色工作台（P1）。
 * 数据严格按主体隔离：门店只看到自己的订单/收益，渠道只看到绑定门店，投资人只看到投资门店。
 */
@Service
public class RoleWorkbenchService {

    private final SubjectAccountMapper subjectAccountMapper;
    private final FundFlowMapper fundFlowMapper;
    private final SettlementRecordMapper settlementRecordMapper;
    private final OrderMapper orderMapper;
    private final BizSubjectMapper bizSubjectMapper;

    public RoleWorkbenchService(SubjectAccountMapper subjectAccountMapper,
                                FundFlowMapper fundFlowMapper,
                                SettlementRecordMapper settlementRecordMapper,
                                OrderMapper orderMapper,
                                BizSubjectMapper bizSubjectMapper) {
        this.subjectAccountMapper = subjectAccountMapper;
        this.fundFlowMapper = fundFlowMapper;
        this.settlementRecordMapper = settlementRecordMapper;
        this.orderMapper = orderMapper;
        this.bizSubjectMapper = bizSubjectMapper;
    }

    /** 工作台概览：余额 + 今日订单 + 今日收益 + 待结算 */
    public Overview overview(Long subjectId) {
        SubjectAccount account = subjectAccountMapper.selectOne(new LambdaQueryWrapper<SubjectAccount>()
                .eq(SubjectAccount::getSubjectId, subjectId));

        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        Long todayOrders = orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                .eq(Order::getStoreSubjectId, subjectId)
                .ge(Order::getCreateTime, dayStart));

        Long pending = settlementRecordMapper.selectSumPending(subjectId);
        Long settled = settlementRecordMapper.selectSumSettleable(subjectId);

        Overview o = new Overview();
        o.setSubjectId(subjectId);
        o.setRoleType(account == null ? null : account.getRoleType());
        o.setAvailableBalance(account == null ? 0L : account.getAvailableBalance());
        o.setFrozenBalance(account == null ? 0L : account.getFrozenBalance());
        o.setTotalIncome(account == null ? 0L : account.getTotalIncome());
        o.setTotalWithdrawn(account == null ? 0L : account.getTotalWithdrawn());
        o.setTodayOrders(todayOrders == null ? 0L : todayOrders);
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
    public List<Order> storeOrders(Long storeSubjectId) {
        return orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStoreSubjectId, storeSubjectId)
                .orderByDesc(Order::getId));
    }

    /** 渠道绑定的门店 */
    public List<BizSubject> channelStores(Long channelSubjectId) {
        List<Long> ids = bizSubjectMapper.selectStoreIdsByChannel(channelSubjectId);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return bizSubjectMapper.selectList(new LambdaQueryWrapper<BizSubject>().in(BizSubject::getId, ids));
    }

    /** 渠道归因订单（绑定门店下的订单） */
    public List<Order> channelOrders(Long channelSubjectId) {
        List<Long> ids = bizSubjectMapper.selectStoreIdsByChannel(channelSubjectId);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .in(Order::getStoreSubjectId, ids)
                .orderByDesc(Order::getId));
    }

    /** 投资人投资的门店 */
    public List<BizSubject> investorStores(Long investorSubjectId) {
        List<Long> ids = bizSubjectMapper.selectStoreIdsByInvestor(investorSubjectId);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return bizSubjectMapper.selectList(new LambdaQueryWrapper<BizSubject>().in(BizSubject::getId, ids));
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


