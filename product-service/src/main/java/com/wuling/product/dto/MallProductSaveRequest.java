package com.wuling.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class MallProductSaveRequest {

    @NotBlank(message = "商品编码不能为空")
    @Size(max = 64, message = "商品编码不能超过64个字符")
    private String productCode;

    @NotNull(message = "请选择商城分类")
    private Long categoryId;

    @NotBlank(message = "商品名称不能为空")
    @Size(max = 128, message = "商品名称不能超过128个字符")
    private String name;

    @Size(max = 255, message = "副标题不能超过255个字符")
    private String subtitle;

    private String specName;
    private String coverImage;
    private List<String> galleryImages;
    private String description;

    @NotNull(message = "请选择归属门店")
    private Long attributionStoreSubjectId;

    private Long attributionChannelSubjectId;
    private Long splitRuleId;
    private String status;
    private Integer sort;
}
