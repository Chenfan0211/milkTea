package com.wuling.subject.dto;

import lombok.Data;

@Data
public class AdminStoreDTO {

    private Long id;
    private String code;
    private String name;
    private String city;
    private String businessStatus;
    private String manager;
    private String location;
    private String investorName;
    private String createTime;
}
