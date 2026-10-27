package com.wuling.user.dto;

/** 供 trade-service 下单时写入订单的收货地址快照，不包含用户与地址主键。 */
public record AddressSnapshotResponse(
        String recipientName,
        String phone,
        String provinceName,
        String cityName,
        String districtName,
        String detailAddress
) {
}