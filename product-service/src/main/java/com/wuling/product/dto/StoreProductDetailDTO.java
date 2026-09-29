package com.wuling.product.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 门店选品详情（小程序端，只读）。
 *
 * <p><b>与消费端详情 {@link ProductDetailDTO} 的区别</b>：本 DTO 额外携带
 * 门店维度信息（{@code listed} 表示该商品在本门店是否已上架），
 * 供门店「选品管理」的详情页只读展示。
 *
 * <p><b>为什么继承而非复制字段</b>：商品自身的展示字段（名称/价格/规格/原料等）
 * 与消费端详情完全同源，继承可避免两处字段各写一遍导致漂移；
 * 门店维度仅 2 个字段（{@code listed} / {@code platformListed}）。
 *
 * <p><b>只读语义</b>：本详情页不支持上下架操作，上下架统一在选品列表页完成。
 * 因此后端不提供对应的写接口，本 DTO 仅用于查询。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StoreProductDetailDTO extends ProductDetailDTO {

    /** 平台是否已上架（恒为 true：列表中只返回平台已上架商品，保留字段便于前端统一渲染）。 */
    private Boolean platformListed;

    /** 该商品在本门店是否已上架（product_store 是否有记录）。 */
    private Boolean listed;
}
