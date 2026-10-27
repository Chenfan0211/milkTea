package com.wuling.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("order_shipping")
public class OrderShipping {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private String recipientName;
    private String phone;
    private String provinceName;
    private String cityName;
    private String districtName;
    private String detailAddress;
    private String courierCompany;
    private String trackingNo;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
