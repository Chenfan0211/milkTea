package com.wuling.finance.dto;

import lombok.Data;

import java.util.List;

/**
 * 投资人「月度分佣」聚合视图（小程序端只读接口）。
 *
 * <p>金额单位统一为「分」，由前端换算成元展示。
 *
 * <p>达标口径：投资人**当月累计分佣额** >= split_rule.investor_threshold_amount
 * 即视为达标，与 {@code SplitRule.resolveInvestorRatio} 完全一致。
 */
@Data
public class InvestorCommissionView {

    /** 当前自然月（yyyy-MM） */
    private String month;

    /** 分佣规则（split_rule 全局生效行） */
    private Rule rule;

    /** 本月汇总 */
    private Current current;

    /** 达标进度（百分比 + 距阈值差额） */
    private Progress progress;

    /** 按门店汇总（销售额 + 分佣额 + 单数） */
    private List<StoreStat> stores;

    /** 近 12 个月逐月记录（倒序） */
    private List<MonthlyRecord> records;

    @Data
    public static class Rule {
        /** 基础比例（万分比） */
        private Integer investorRatio;
        /** 当月达标额（分） */
        private Long investorThresholdAmount;
        /** 达标后比例（万分比） */
        private Integer investorRatioAfter;
        /** 是否启用阈值规则（阈值 > 0 且达标比例 > 0） */
        private boolean thresholdEnabled;
    }

    @Data
    public static class Current {
        /** 当月累计分佣额（分） */
        private Long commissionAmount;
        /** 当月门店销售额（分，已支付且非全额退款口径） */
        private Long salesAmount;
        /** 当月已结算分佣额（分） */
        private Long settledAmount;
        /** 当月待结算分佣额（分） */
        private Long pendingAmount;
        /** 本月当前生效比例（万分比） */
        private Integer effectiveRatio;
        /** 是否已达标 */
        private boolean reached;
    }

    @Data
    public static class Progress {
        /** 进度百分比 0-100（超额封顶） */
        private int percent;
        /** 距阈值差额（分，已达标为 0） */
        private Long remainToThreshold;
    }

    @Data
    public static class StoreStat {
        private Long id;
        private String name;
        /** 门店销售额（分） */
        private Long salesAmount;
        /** 门店分佣额（分） */
        private Long commissionAmount;
        /** 门店订单数 */
        private Long orderCount;
    }

    @Data
    public static class MonthlyRecord {
        /** 月份（yyyy-MM） */
        private String month;
        /** 该月分佣额（分） */
        private Long commissionAmount;
        /** 该月门店销售额（分） */
        private Long salesAmount;
        /** 该月生效比例（万分比，月末口径） */
        private Integer effectiveRatio;
        /** 该月是否达标 */
        private boolean reached;
    }
}
