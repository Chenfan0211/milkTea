package com.wuling.finance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("split_snapshot")
public class SplitSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String snapshotNo;
    private Long orderId;
    private String orderNo;
    private Integer itemCount;
    private Long platformAmount;
    private Long storeAmount;
    private Long channelAmount;
    private Long investorAmount;
    private Long supplierAmount;
    private Long platformCommission;
    private Long platformBonus;
    private String totalCheck;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
