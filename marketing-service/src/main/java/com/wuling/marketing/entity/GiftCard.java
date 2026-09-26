package com.wuling.marketing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("gift_card")
public class GiftCard {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String cardNo;
    private Long denominationId;
    /** 一单一卡绑定；历史卡此字段可为空。 */
    private Long orderId;
    private String status;
    private Long ownerUserId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 以下均为历史/展示元数据，来自 gift_card_denomination，不属于 gift_card 表。 */
    @TableField(exist = false)
    private String cardName;
    @TableField(exist = false)
    private String cardImage;
    @TableField(exist = false)
    private String groupTitle;
    @TableField(exist = false)
    private Long amount;
    @TableField(exist = false)
    private Long salePrice;

    @TableLogic
    private Integer deleted;
}
