package com.wuling.marketing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("stored_value_order")
public class StoredValueOrder {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private Long userId;
    private Long packageId;
    private Long amount;
    private String payStatus;

    /** 微信支付交易号（第 15 期新增，V24 迁移）；对账与退款必需 */
    private String transactionId;

    /** 支付者 openid（第 15 期新增，V24 迁移）；微信退款要求原路退回 */
    private String payerOpenid;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 订单来源分类（非数据库字段）：供小程序订单页统一聚合与页签过滤 */
    @TableField(exist = false)
    private String category = "stored-value";

    /**
     * 订单状态（非数据库字段）：储值充值只有「待支付 / 已完成」两种状态，
     * 由 pay_status 推导后下发，避免与 pay_status 语义重叠再建一列。
     */
    @TableField(exist = false)
    private String status;

    @TableLogic
    private Integer deleted;
}
