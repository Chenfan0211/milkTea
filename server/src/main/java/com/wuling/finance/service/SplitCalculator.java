package com.wuling.finance.service;

import com.wuling.finance.entity.SplitRule;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 分账计算（平台 / 门店 / 资源方 / 投资人 / 供应商 / 团长）。
 *
 * <p><b>计费口径（固定金额 + 成本直给模型）</b>：
 * <ul>
 *   <li>门店提成 = 门店每件提成（分） × 商品件数；商城订单（mall）不发门店提成（=0）；</li>
 *   <li>资源方提成 = 有渠道归因 ? 资源方每件提成（分） × 件数 : 0；</li>
 *   <li>供应商成本 = Σ(每条明细商品成本价 costPrice × 该明细件数 quantity)，按商品成本直给；</li>
 *   <li>平台提成 = Σ(每条明细平台提成 platformCommission × 该明细件数 quantity)；</li>
 *   <li>分销佣金（团长） = Σ(每条明细分销佣金 distributionCommission × 件数)；</li>
 *   <li>投资人 = max(0, 实付 − 门店 − 资源方 − 供应商成本 − 平台提成) × 投资人比例（万分比）；</li>
 *   <li>平台 = 实付 − 其余各方（尾差兜底，可能为负，平台承担）。</li>
 * </ul>
 *
 * <p>团长佣金：由 LedgerService 决定归团长主体还是并入平台尾差；本计算器只负责
 * 把 {@code leader} 从平台尾差中切出，保证「平台 + 门店 + 资源方 + 投资人 + 供应商 + 团长 = 实付」。
 *
 * <p>投资人「当月累计达标后比例」：累计分账额达到 {@code investorThresholdAmount} 后，
 * 投资人比例改用 {@code investorRatioAfter}（见 {@link SplitRule#resolveInvestorRatio}）。
 */
@Service
public class SplitCalculator {

    public record SplitAmount(Long platform,
                              Long store,
                              Long channel,
                              Long investor,
                              Long supplier,
                              Long platformCommission,
                              Long platformBonus,
                              Long leader) {

        /** 兼容旧调用：无团长分佣时 leader = 0。 */
        public SplitAmount(Long platform, Long store, Long channel, Long investor, Long supplier,
                           Long platformCommission, Long platformBonus) {
            this(platform, store, channel, investor, supplier, platformCommission, platformBonus, 0L);
        }
    }

    /**
     * 订单明细行（用于供应商成本、平台提成、分销佣金按商品分摊）。
     *
     * @param supplierSubjectId  该明细所属供应商主体（null 表示未绑定）
     * @param amount             该明细的实付小计（分）
     * @param platformCommission 该商品平台提成单价（分/件）
     * @param costPrice          该商品成本单价（分/件，供应商分账直给）
     * @param quantity           该明细件数；成本与平台提成均按「单价 × 件数」计
     * @param distributionCommission 该商品分销佣金单价（分/件，商城订单归团长）
     */
    public record LineItem(Long supplierSubjectId, long amount, Long platformCommission, Long costPrice,
                           Integer quantity, Long distributionCommission) {

        /** 兼容旧调用：未提供件数时按 1 件处理，无分销佣金 */
        public LineItem(Long supplierSubjectId, long amount, Long platformCommission, Long costPrice) {
            this(supplierSubjectId, amount, platformCommission, costPrice, 1, null);
        }

        public LineItem(Long supplierSubjectId, long amount, Long platformCommission, Long costPrice,
                        Integer quantity) {
            this(supplierSubjectId, amount, platformCommission, costPrice, quantity, null);
        }
    }

    /**
     * @param paidAmount         实付金额（分）
     * @param itemCount          商品件数
     * @param hasChannel         是否有渠道归因（无渠道则渠道部分归平台）
     * @param rule               分账规则
     * @param platformCommission 平台佣金（分，已废弃——平台提成改为按明细汇总，保留参数向后兼容）
     */
    public SplitAmount calc(long paidAmount, int itemCount, boolean hasChannel,
                            SplitRule rule, long platformCommission) {
        return calc(paidAmount, itemCount, hasChannel, rule, platformCommission, List.of(), null, false);
    }

    /**
     * 带明细分摊的分账计算（不启用「投资人达标后比例」）。
     */
    public SplitAmount calc(long paidAmount, int itemCount, boolean hasChannel,
                            SplitRule rule, long platformCommission, List<LineItem> items) {
        return calc(paidAmount, itemCount, hasChannel, rule, platformCommission, items, null, false);
    }

    /**
     * 带明细分摊的分账计算（支持投资人「当月累计达标后比例」）。
     *
     * @param accumulatedInvestorAmount 投资人当月累计已分账金额（分）；null 表示不启用阈值判定
     */
    public SplitAmount calc(long paidAmount, int itemCount, boolean hasChannel,
                            SplitRule rule, long platformCommission, List<LineItem> items,
                            Long accumulatedInvestorAmount) {
        return calc(paidAmount, itemCount, hasChannel, rule, platformCommission, items,
                accumulatedInvestorAmount, false);
    }

    /**
     * 带明细分摊的分账计算（支持商城模式：不发门店提成 + 分销佣金切出）。
     *
     * @param mallOrder              商城订单标记：true 时门店提成恒为 0
     * @param accumulatedInvestorAmount 投资人当月累计已分账金额（分）；null 表示不启用阈值判定
     */
    public SplitAmount calc(long paidAmount, int itemCount, boolean hasChannel,
                            SplitRule rule, long platformCommission, List<LineItem> items,
                            Long accumulatedInvestorAmount, boolean mallOrder) {
        if (rule == null) {
            long commission = sumPlatformCommission(items);
            long leader = sumDistributionCommission(items);
            return new SplitAmount(paidAmount - leader, 0L, 0L, 0L, 0L, commission,
                    paidAmount - commission - leader, leader);
        }
        // 门店 / 资源方：固定金额，按商品件数计；商城订单不发门店提成
        long store = mallOrder ? 0L : perItem(rule.getStoreRatio()) * itemCount;
        long channel = hasChannel ? perItem(rule.getChannelRatio()) * itemCount : 0L;

        // 供应商成本：成本单价 × 件数 后汇总（只加单价会把多件订单的成本漏算）
        long supplier = sumCostPrice(items);

        // 平台提成：提成单价 × 件数 后汇总
        long commission = sumPlatformCommission(items);

        // 分销佣金：单价 × 件数 后汇总（商城订单；门店订单无此字段自然为 0）
        long leader = sumDistributionCommission(items);

        // 投资人计费基础 = 实付 − 门店 − 资源方 − 供应商成本 − 平台提成
        long base = paidAmount - store - channel - supplier - commission;
        int effectiveInvestorRatio = rule.resolveInvestorRatio(accumulatedInvestorAmount);
        long investor = base > 0 ? base * effectiveInvestorRatio / 10000 : 0L;

        // 平台 = 尾差兜底（可为负）；团长佣金从平台尾差中切出
        long platform = paidAmount - store - channel - supplier - investor - leader;
        long bonus = platform - commission;
        return new SplitAmount(platform, store, channel, investor, supplier, commission, bonus, leader);
    }

    private long perItem(Integer v) {
        return v == null ? 0L : v;
    }

    /** 平台提成合计 = Σ(提成单价 × 件数) */
    private long sumPlatformCommission(List<LineItem> items) {
        long sum = 0L;
        if (items == null) {
            return sum;
        }
        for (LineItem item : items) {
            if (item != null && item.platformCommission() != null) {
                sum += item.platformCommission() * quantityOf(item);
            }
        }
        return sum;
    }

    /** 分销佣金合计 = Σ(分销佣金单价 × 件数) */
    private long sumDistributionCommission(List<LineItem> items) {
        long sum = 0L;
        if (items == null) {
            return sum;
        }
        for (LineItem item : items) {
            if (item != null && item.distributionCommission() != null) {
                sum += item.distributionCommission() * quantityOf(item);
            }
        }
        return sum;
    }

    /** 供应商成本合计 = Σ(成本单价 × 件数) */
    private long sumCostPrice(List<LineItem> items) {
        long sum = 0L;
        if (items == null) {
            return sum;
        }
        for (LineItem item : items) {
            if (item != null && item.costPrice() != null) {
                sum += item.costPrice() * quantityOf(item);
            }
        }
        return sum;
    }

    /** 件数：null 或 <= 0 时按 1 件兜底，避免整行金额被算成 0 */
    private long quantityOf(LineItem item) {
        Integer q = item.quantity();
        return q == null || q <= 0 ? 1L : q;
    }
}