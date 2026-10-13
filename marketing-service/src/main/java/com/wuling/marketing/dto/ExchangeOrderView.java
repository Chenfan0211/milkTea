package com.wuling.marketing.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 我的兑换记录展示视图。
 *
 * <p><b>为什么不直接返回实体 {@code ExchangeOrder}</b>：兑换记录页需要展示
 * 「兑换了什么商品」，而 {@code exchange_order} 表只存 {@code points_product_id}，
 * 商品名/图片在 {@code points_product} 表。原实现裸返回实体，接口里根本没有商品字段，
 * 前端只能用「兑换单号」兜底当商品名显示 —— 表现为记录只显示一串 CZ 单号。
 *
 * <p>这里在服务端一次性 left join 出商品信息，前端无需再二次请求。
 */
@Data
public class ExchangeOrderView {

    private Long id;
    private String exchangeNo;
    private Long pointsProductId;
    /** 商品名称，来自 points_product.name（商品被删除时可能为 null） */
    private String productName;
    /** 商品图片，来自 points_product.image */
    private String productImage;
    /** 本次兑换消耗的时光币总数 */
    private Long points;
    /** 本次兑换数量 */
    private Integer quantity;
    private String pickupCode;
    private String status;
    private LocalDateTime createTime;
}
