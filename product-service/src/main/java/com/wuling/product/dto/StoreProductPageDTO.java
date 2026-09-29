package com.wuling.product.dto;

import com.wuling.common.api.PageResult;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 门店选品分页结果（含状态计数）。
 *
 * <p><b>为什么不能只返回 {@link PageResult}</b>：选品页的状态 Tab（全部 / 已上架 / 已下架）
 * 需要三个计数，而分页只返回当前页记录数，前端拿不到全量分布。
 *
 * <p><b>为什么计数必须与 total 同源</b>：早期实现由前端额外发两次「listed=true / false」
 * 的请求来统计，且**未带关键词与分类筛选** —— 结果 total 走筛选口径、计数走全量口径，
 * 三者恒不自洽（实测 全部 1 / 已上架 27 / 已下架 1，而 27+1=28≠1）。
 * 现由后端在**同一筛选条件**下一次性算出，天然满足
 * {@code listedTotal + unlistedTotal == total}。
 *
 * <p><b>口径</b>：三个计数均基于当前请求的筛选条件（keyword / categoryId），
 * 但**不含** listed 本身 —— 否则「全部」在被 listed 过滤后就失去意义。
 *
 * <p><b>分类分布 {@code categoryCounts}</b>：与上述口径**完全一致**
 * （同为 keyword 过滤后、listed 过滤前的集合），因此
 * {@code Σ categoryCounts.count == listedTotal + unlistedTotal}。
 * 前端分类 Tab 直接用该分布渲染，不再按「当前页数据」统计 ——
 * 后者在分页下只统计当前页，数字会随翻页变化，且与「全部」口径不一致。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StoreProductPageDTO extends PageResult<StoreProductDTO> {

    /** 当前筛选条件下「本店已上架」的商品数（不含 listed 自身过滤）。 */
    private long listedTotal;

    /** 当前筛选条件下「本店未上架」的商品数（不含 listed 自身过滤）。 */
    private long unlistedTotal;

    /**
     * 分类分布（按商品数降序）。
     *
     * <p>与 {@code listedTotal} / {@code unlistedTotal} 同源，满足总和相等，
     * 供前端分类 Tab 直接渲染（不随分页变化）。
     */
    private List<CategoryCount> categoryCounts;

    /** 单个分类的商品数。 */
    @Data
    public static class CategoryCount {

        private Long categoryId;
        /** 分类名；分类缺失时为 "-"。 */
        private String label;
        private long count;
    }

    /**
     * 构造选品分页结果。
     *
     * @param page           已按 listed 过滤的分页数据（total 为当前筛选下的总数）
     * @param listedTotal    当前筛选下已上架数量
     * @param unlistedTotal  当前筛选下未上架数量
     * @param categoryCounts 当前筛选下的分类分布
     */
    public static StoreProductPageDTO of(PageResult<StoreProductDTO> page,
                                         long listedTotal,
                                         long unlistedTotal,
                                         List<CategoryCount> categoryCounts) {
        StoreProductPageDTO dto = new StoreProductPageDTO();
        dto.setRecords(page.getRecords());
        dto.setCurrent(page.getCurrent());
        dto.setSize(page.getSize());
        dto.setTotal(page.getTotal());
        dto.setListedTotal(listedTotal);
        dto.setUnlistedTotal(unlistedTotal);
        dto.setCategoryCounts(categoryCounts == null ? List.of() : categoryCounts);
        return dto;
    }
}
