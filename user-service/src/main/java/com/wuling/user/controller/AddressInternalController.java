package com.wuling.user.controller;

import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.user.dto.AddressSnapshotResponse;
import com.wuling.user.service.AddressService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 收货地址内部查询接口。
 *
 * <p>仅供 trade-service 下单时获取地址快照；不经过网关或小程序 JWT 拦截器。
 * userId 与地址 ID 同时作为归属条件。响应沿用内部调用约定：裸 JSON 且带
 * {@code found} 标记，不存在或非本人地址返回 {@code {"found": false}}。
 */
@RestController
@RequestMapping("/internal/addresses")
public class AddressInternalController {

    private final AddressService addressService;

    public AddressInternalController(AddressService addressService) {
        this.addressService = addressService;
    }

    @GetMapping("/{id}")
    public Map<String, Object> snapshot(@PathVariable Long id,
                                        @RequestParam Long userId) {
        try {
            AddressSnapshotResponse address = addressService.snapshot(userId, id);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("found", true);
            result.put("recipientName", address.recipientName());
            result.put("phone", address.phone());
            result.put("provinceName", address.provinceName());
            result.put("cityName", address.cityName());
            result.put("districtName", address.districtName());
            result.put("detailAddress", address.detailAddress());
            return result;
        } catch (BusinessException e) {
            if (e.getCode() == ResultCode.NOT_FOUND) {
                return Map.of("found", false);
            }
            throw e;
        }
    }
}