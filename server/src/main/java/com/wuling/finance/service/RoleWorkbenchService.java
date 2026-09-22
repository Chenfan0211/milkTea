package com.wuling.finance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.finance.entity.FundFlow;
import com.wuling.finance.entity.SettlementRecord;
import com.wuling.finance.entity.SubjectAccount;
import com.wuling.finance.mapper.FundFlowMapper;
import com.wuling.finance.mapper.SettlementRecordMapper;
import com.wuling.finance.mapper.SubjectAccountMapper;
import com.wuling.subject.port.SubjectQueryPort;

import com.wuling.finance.port.TradeOrderQueryPort;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
    /** 第 7 期：订单查询改走端口（trade 已拆出） */
    private final TradeOrderQueryPort tradeOrderQueryPort;
    /** 第 12 期：主体查询改走端口 */
    private final SubjectQueryPort subjectQueryPort;

    public RoleWorkbenchService(SubjectAccountMapper subjectAccountMapper,
                                FundFlowMapper fundFlowMapper,
                                SettlementRecordMapper settlementRecordMapper,
                                TradeOrderQueryPort tradeOrderQueryPort,
                                SubjectQueryPort subjectQueryPort) {
        this.subjectAccountMapper = subjectAccountMapper;
        this.fundFlowMapper = fundFlowMapper;
        this.settlementRecordMapper = settlementRecordMapper;
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


