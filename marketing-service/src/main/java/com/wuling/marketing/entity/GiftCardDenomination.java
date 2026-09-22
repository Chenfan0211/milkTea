package com.wuling.marketing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("gift_card_denomination")
public class GiftCardDenomination {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    /** 分组 id */
    private String groupId;
    /** 分组标题 */
    private String groupTitle;
    /** 卡面名称 */
    private String cardName;
    /** 卡面图片 */
    private String cardImage;
    private String name;
    private Long amount;
    /** 售价（分） */
    private Long salePrice;
    private String status;
    private Integer sort;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
