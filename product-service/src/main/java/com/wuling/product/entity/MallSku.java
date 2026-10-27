package com.wuling.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mall_sku")
public class MallSku {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long productId;
    private String skuCode;
    private String specValue;
    private String image;
    private Long salePrice;
    private Long originalPrice;
    private Long costPrice;
    private Long platformCommission;
    private Long distributionCommission;
    private Long supplierSubjectId;
    private Integer stock;
    private Integer lockedStock;
    private Integer sort;
    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;

    public int availableStock() {
        int total = stock == null ? 0 : stock;
        int locked = lockedStock == null ? 0 : lockedStock;
        return Math.max(0, total - locked);
    }
}

