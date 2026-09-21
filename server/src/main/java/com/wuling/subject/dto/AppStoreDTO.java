package com.wuling.subject.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class AppStoreDTO {

    private Long id;
    private String code;
    private String name;
    private String city;
    private String address;
    private String phone;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private String storeType;
    private String businessStatus;
    private String manager;
    private Long investorSubjectId;
    private String businessHours;
    private List<String> modes;
    private String promotion;
    private Integer queueCount;
}
