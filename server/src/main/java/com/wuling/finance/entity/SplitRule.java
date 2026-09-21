package com.wuling.finance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("split_rule")
public class SplitRule {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String scope;
    private Long productId;
    private Integer platformRatio;
    private Integer storeRatio;
    private Integer channelRatio;
    private Integer investorRatio;
    private Integer supplierRatio;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;

    public int totalRatio() {
        return nz(platformRatio) + nz(storeRatio) + nz(channelRatio) + nz(investorRatio) + nz(supplierRatio);
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
