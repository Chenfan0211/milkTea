package com.wuling.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 新增/编辑收货地址请求；用户归属只取自登录上下文。 */
@Data
public class AddressRequest {

    @NotBlank(message = "请输入收件人姓名")
    @Size(max = 32, message = "收件人姓名不能超过32个字符")
    private String recipientName;

    @NotBlank(message = "请输入手机号")
    @Size(max = 20, message = "手机号不能超过20个字符")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "请输入正确的手机号")
    private String phone;

    @NotBlank(message = "请选择省份")
    @Size(max = 32, message = "省份编码不能超过32个字符")
    private String provinceCode;

    @NotBlank(message = "请选择省份")
    @Size(max = 64, message = "省份名称不能超过64个字符")
    private String provinceName;

    @NotBlank(message = "请选择城市")
    @Size(max = 32, message = "城市编码不能超过32个字符")
    private String cityCode;

    @NotBlank(message = "请选择城市")
    @Size(max = 64, message = "城市名称不能超过64个字符")
    private String cityName;

    @NotBlank(message = "请选择区县")
    @Size(max = 32, message = "区县编码不能超过32个字符")
    private String districtCode;

    @NotBlank(message = "请选择区县")
    @Size(max = 64, message = "区县名称不能超过64个字符")
    private String districtName;

    @NotBlank(message = "请输入详细地址")
    @Size(max = 255, message = "详细地址不能超过255个字符")
    private String detailAddress;

    @Size(max = 16, message = "地址标签不能超过16个字符")
    private String tag;

    /** 新增时首条地址忽略此值并自动默认；编辑时 false 不能取消当前唯一默认。 */
    private Boolean isDefault;
}