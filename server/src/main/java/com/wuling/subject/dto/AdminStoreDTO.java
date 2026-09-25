package com.wuling.subject.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class AdminStoreDTO {

    private Long id;
    private String code;
    private String name;
    private String city;
    private String businessStatus;
    private String manager;
    private String location;
    private String storeType;
    private String address;
    private String investorName;
    private Long investorSubjectId;
    private String phone;
    private String type;
    private Long boundUserId;
    private String boundUserName;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private String createTime;
}
