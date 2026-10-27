package com.wuling.user.dto;

import com.wuling.user.entity.AppUserAddress;

import java.time.LocalDateTime;

/** 收货地址响应。 */
public record AddressResponse(
        Long id,
        String recipientName,
        String phone,
        String provinceCode,
        String provinceName,
        String cityCode,
        String cityName,
        String districtCode,
        String districtName,
        String detailAddress,
        String tag,
        boolean isDefault,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {

    public static AddressResponse from(AppUserAddress address) {
        return new AddressResponse(
                address.getId(),
                address.getRecipientName(),
                address.getPhone(),
                address.getProvinceCode(),
                address.getProvinceName(),
                address.getCityCode(),
                address.getCityName(),
                address.getDistrictCode(),
                address.getDistrictName(),
                address.getDetailAddress(),
                address.getTag(),
                Integer.valueOf(1).equals(address.getIsDefault()),
                address.getCreateTime(),
                address.getUpdateTime()
        );
    }
}