package com.wuling.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MallCategorySaveRequest {

    @NotBlank(message = "分类编码不能为空")
    @Size(max = 64, message = "分类编码不能超过64个字符")
    private String code;

    @NotBlank(message = "分类名称不能为空")
    @Size(max = 64, message = "分类名称不能超过64个字符")
    private String name;

    private Integer sort;

    private String status;
}
