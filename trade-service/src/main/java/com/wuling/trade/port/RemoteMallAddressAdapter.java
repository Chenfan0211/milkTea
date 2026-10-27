package com.wuling.trade.port;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class RemoteMallAddressAdapter implements MallAddressPort {
    private static final Logger log = LoggerFactory.getLogger(RemoteMallAddressAdapter.class);
    private final RestClient userRestClient;

    public RemoteMallAddressAdapter(@Qualifier("userInternalRestClient") RestClient userRestClient) {
        this.userRestClient = userRestClient;
    }

    @Override
    @SuppressWarnings("unchecked")
    public AddressView findOwned(Long userId, Long addressId) {
        try {
            Map<String, Object> body = userRestClient.get()
                    .uri("/internal/addresses/{id}?userId={userId}", addressId, userId)
                    .retrieve().body(Map.class);
            if (body == null || !Boolean.TRUE.equals(body.get("found"))) return null;
            return new AddressView(text(body.get("recipientName")), text(body.get("phone")),
                    text(body.get("provinceName")), text(body.get("cityName")),
                    text(body.get("districtName")), text(body.get("detailAddress")));
        } catch (Exception e) {
            log.error("查询收货地址失败 userId={} addressId={} err={}", userId, addressId, e.getMessage());
            throw new IllegalStateException("收货地址服务暂不可用，请稍后重试", e);
        }
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
