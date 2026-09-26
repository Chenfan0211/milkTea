package com.wuling.marketing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("gift_card_order")
public class GiftCardOrder {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private Long userId;
    private Long denominationId;
    private Long amount;
    /** 微信支付交易号（流水订单号）；模拟支付保留 DEMO 前缀。 */
    private String transactionId;
    /** 待支付订单超时时间。 */
    private LocalDateTime expireTime;
    private String payStatus;
    private String status;
    private String cancelType;
    private Long refundAmount;
    private LocalDateTime payTime;
    private String verifyStatus;
    private LocalDateTime verifyTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 订单来源分类（非数据库字段）：供小程序订单页统一聚合与页签过滤 */
    @TableField(exist = false)
    private String category = "gift-card";

    /** 历史/展示元数据，来自 gift_card_denomination，不属于 gift_card_order 表。 */
    @TableField(exist = false)
    private String cardName;
    @TableField(exist = false)
    private String cardImage;
    @TableField(exist = false)
    private String groupTitle;
    @TableField(exist = false)
    private Long salePrice;
    @TableField(exist = false)
    private String refundStatus;
    @TableField(exist = false)
    private String refundFailReason;

    @TableLogic
    private Integer deleted;
}
