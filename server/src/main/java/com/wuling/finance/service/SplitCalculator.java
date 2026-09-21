package com.wuling.finance.service;

import com.wuling.finance.entity.SplitRule;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 五方分账计算。
 * 约定：
 * - 分账基数 = 订单实付金额（分），时光币兑换与抵扣部分不参与分账；
 * - 比例 = 万分比（0~10000），五方合计必须为 10000；
 * - 金额精确到分，尾差归平台；
 * - 供应商份额按订单明细逐条分摊后汇总（多商品订单归属准确）。
 */
@Service
public class SplitCalculator {

    public record SplitAmount(Long platform,
                              Long store,
                              Long channel,
                              Long investor,
                              Long supplier,
                              Long platformCommission,
                              Long platformBonus) {
    }

    /**
     * 订单明细（用于供应商分摊）。
     *
     * @param supplierSubjectId 该明细所属供应商主体（null 表示未绑定）
     * @param amount            该明细的实付小计（分）
     */
    public record LineItem(Long supplierSubjectId, long amount) {
    }

    /**
     * @param paidAmount         实付金额（分）
     * @param itemCount          商品件数
     * @param hasChannel         是否有渠道归因（无渠道则渠道部分归平台）
     * @param rule               分账规则
     * @param platformCommission 平台佣金（分）
     */
    public SplitAmount calc(long paidAmount, int itemCount, boolean hasChannel,
                            SplitRule rule, long platformCommission) {
        return calc(paidAmount, itemCount, hasChannel, rule, platformCommission, List.of());
    }

    /**
     * 带明细分摊的分账计算。
     * 供应商份额 = Σ(每条明细金额 × supplierRatio / 10000)，逐条向下取整，尾差同样归平台。
     */
    public SplitAmount calc(long paidAmount, int itemCount, boolean hasChannel,
                            SplitRule rule, long platformCommission, List<LineItem> items) {
        if (rule == null) {
            return new SplitAmount(paidAmount, 0L, 0L, 0L, 0L, platformCommission, paidAmount - platformCommission);
        }
        int total = rule.totalRatio();
        if (total != 10000) {
            throw new IllegalStateException("分账比例合计必须为 10000（万分比），当前为 " + total);
        }
        long store = ratio(paidAmount, rule.getStoreRatio());
        long channel = hasChannel ? ratio(paidAmount, rule.getChannelRatio()) : 0L;
        long investor = ratio(paidAmount, rule.getInvestorRatio());

        // 供应商：按明细分摊，避免多商品订单归属失真
        long supplier = 0L;
        for (LineItem item : items) {
            if (item != null && item.supplierSubjectId() != null) {
                supplier += ratio(item.amount(), rule.getSupplierRatio());
            }
        }

        // 尾差归平台：平台 = 实付 - 其余四方
        long platform = paidAmount - store - channel - investor - supplier;
        long bonus = platform - platformCommission;
        return new SplitAmount(platform, store, channel, investor, supplier, platformCommission, bonus);
    }

    /** 万分比取整（向下取整到分），保证各方之和不超过实付金额 */
    private long ratio(long amount, Integer ratio) {
        if (ratio == null || ratio == 0) {
            return 0L;
        }
        return amount * ratio / 10000;
    }
}
