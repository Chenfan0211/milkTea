package com.wuling.subject.controller;

import com.wuling.common.api.Result;
import com.wuling.subject.dto.AppStoreDTO;
import com.wuling.subject.service.StoreService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/app")
public class AppStoreController {

    private final StoreService storeService;

    public AppStoreController(StoreService storeService) {
        this.storeService = storeService;
    }

    @GetMapping("/stores")
    public Result<List<AppStoreDTO>> stores() {
        return Result.ok(storeService.listAppStores());
    }
}
