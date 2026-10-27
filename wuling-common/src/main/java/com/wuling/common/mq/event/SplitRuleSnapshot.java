package com.wuling.common.mq.event;

import java.io.Serializable;

/** Immutable split-rule values captured when an order is created. */
public class SplitRuleSnapshot implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long ruleId;
    private Integer storeRatio;
    private Integer channelRatio;
    private Integer investorRatio;
    private Long investorThresholdAmount;
    private Integer investorRatioAfter;
    /** 归属团长主体 ID（商城订单；无团长时为空，分销佣金并入平台尾差） */
    private Long leaderSubjectId;
    /** 归属团长用户 ID（商城订单；无团长时为空） */
    private Long leaderUserId;

    public SplitRuleSnapshot() {}

    public SplitRuleSnapshot(Long ruleId, Integer storeRatio, Integer channelRatio,
                             Integer investorRatio, Long investorThresholdAmount,
                             Integer investorRatioAfter) {
        this(ruleId, storeRatio, channelRatio, investorRatio, investorThresholdAmount,
                investorRatioAfter, null, null);
    }

    public SplitRuleSnapshot(Long ruleId, Integer storeRatio, Integer channelRatio,
                             Integer investorRatio, Long investorThresholdAmount,
                             Integer investorRatioAfter, Long leaderSubjectId, Long leaderUserId) {
        this.ruleId = ruleId;
        this.storeRatio = storeRatio;
        this.channelRatio = channelRatio;
        this.investorRatio = investorRatio;
        this.investorThresholdAmount = investorThresholdAmount;
        this.investorRatioAfter = investorRatioAfter;
        this.leaderSubjectId = leaderSubjectId;
        this.leaderUserId = leaderUserId;
    }

    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public Integer getStoreRatio() { return storeRatio; }
    public void setStoreRatio(Integer storeRatio) { this.storeRatio = storeRatio; }
    public Integer getChannelRatio() { return channelRatio; }
    public void setChannelRatio(Integer channelRatio) { this.channelRatio = channelRatio; }
    public Integer getInvestorRatio() { return investorRatio; }
    public void setInvestorRatio(Integer investorRatio) { this.investorRatio = investorRatio; }
    public Long getInvestorThresholdAmount() { return investorThresholdAmount; }
    public void setInvestorThresholdAmount(Long investorThresholdAmount) { this.investorThresholdAmount = investorThresholdAmount; }
    public Integer getInvestorRatioAfter() { return investorRatioAfter; }
    public void setInvestorRatioAfter(Integer investorRatioAfter) { this.investorRatioAfter = investorRatioAfter; }
    public Long getLeaderSubjectId() { return leaderSubjectId; }
    public void setLeaderSubjectId(Long leaderSubjectId) { this.leaderSubjectId = leaderSubjectId; }
    public Long getLeaderUserId() { return leaderUserId; }
    public void setLeaderUserId(Long leaderUserId) { this.leaderUserId = leaderUserId; }
}