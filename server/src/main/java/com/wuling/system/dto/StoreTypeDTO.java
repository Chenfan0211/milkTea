package com.wuling.system.dto;

import lombok.Data;

@Data
public class StoreTypeDTO {

    private Long id;
    private String code;
    private String name;
    private Integer sort;
    private Boolean enabled;
}
