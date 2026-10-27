package com.wuling.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("order_item")
public class OrderItem {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    /** Mall SKU id; null for store orders. */
    private Long mallSkuId;
    private String productId;
    private String productName;
    private String specSnapshot;
    private String image;
    private Long unitPrice;
    private Long costPrice;
    private Long platformCommission;
    private Long distributionCommission;
    private Long supplierSubjectId;
    private Long originalPrice;
    private Integer quantity;
    private Long subTotal;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
