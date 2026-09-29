package com.wuling.product.dto;

import lombok.Data;

/**
 * 门店选品列表项（小程序端）。
 *
 * <p>语义（2026-09 口径）：
 * <ul>
 *   <li>基础数据 = 平台已上架商品（{@code product.on_sale = 1}）；</li>
 *   <li>{@code listed=true} —— 该商品在此门店已上架（product_store 有记录）；</li>
 *   <li>{@code listed=false} —— 该商品在此门店未上架（product_store 无记录）。</li>
 * </ul>
 */
@Data
public class StoreProductDTO {

    /** 商品业务编号（product.product_id / code，前端用作路由与展示 ID） */
    private String id;
    /** 商品主键（product.id，写入 product_store 用） */
    private Long productId;
    private String name;
    /** 售价（分）；前端按元展示 */
    private Long price;
    private Long originalPrice;
    private String image;
    private Long categoryId;
    private String categoryLabel;
    /** 平台是否已上架（恒为 true，保留字段便于前端统一渲染）。 */
    private Boolean platformListed;
    /** 该门店是否已上架。 */
    private Boolean listed;
}
