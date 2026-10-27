package com.wuling.trade.port;

public interface MallAddressPort {
    AddressView findOwned(Long userId, Long addressId);

    record AddressView(String recipientName, String phone, String provinceName,
                       String cityName, String districtName, String detailAddress) {}
}
