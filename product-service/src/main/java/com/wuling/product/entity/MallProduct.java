package com.wuling.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mall_product")
public class MallProduct {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String productCode;
    private Long categoryId;
    private String name;
    private String subtitle;
    private String specName;
    private String coverImage;
    /** JSON 数组文本，落库格式与 V86 迁移保持一致。 */
    private String galleryImages;
    private String description;
    private Long attributionStoreSubjectId;
    private Long attributionChannelSubjectId;
    private Long splitRuleId;
    private String status;
    private Integer sort;
    private Integer salesCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
