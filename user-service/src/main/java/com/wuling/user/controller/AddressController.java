package com.wuling.user.controller;

import com.wuling.common.api.Result;
import com.wuling.security.CurrentUser;
import com.wuling.user.dto.AddressRequest;
import com.wuling.user.dto.AddressResponse;
import com.wuling.user.service.AddressService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 小程序收货地址接口；用户身份一律取自 JWT。 */
@RestController
@RequestMapping("/api/v1/app/addresses")
public class AddressController {

    private final AddressService addressService;

    public AddressController(AddressService addressService) {
        this.addressService = addressService;
    }

    @GetMapping
    public Result<List<AddressResponse>> list() {
        return Result.ok(addressService.list(CurrentUser.require()));
    }

    @PostMapping
    public Result<AddressResponse> create(@Valid @RequestBody AddressRequest request) {
        return Result.ok(addressService.create(CurrentUser.require(), request));
    }

    @PutMapping("/{id}")
    public Result<AddressResponse> update(@PathVariable Long id,
                                          @Valid @RequestBody AddressRequest request) {
        return Result.ok(addressService.update(CurrentUser.require(), id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        addressService.delete(CurrentUser.require(), id);
        return Result.ok();
    }

    @PutMapping("/{id}/default")
    public Result<Void> setDefault(@PathVariable Long id) {
        addressService.setDefault(CurrentUser.require(), id);
        return Result.ok();
    }
}