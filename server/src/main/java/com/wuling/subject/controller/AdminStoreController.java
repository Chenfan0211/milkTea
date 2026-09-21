package com.wuling.subject.controller;

import com.wuling.common.api.PageResult;
import com.wuling.common.api.Result;
import com.wuling.subject.dto.AdminStoreDTO;
import com.wuling.subject.service.StoreService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/subject")
public class AdminStoreController {

    private final StoreService storeService;

    public AdminStoreController(StoreService storeService) {
        this.storeService = storeService;
    }

    @GetMapping("/stores")
    public Result<PageResult<AdminStoreDTO>> stores(@RequestParam(defaultValue = "1") long current,
                                                    @RequestParam(defaultValue = "10") long size,
                                                    @RequestParam(required = false) String search) {
        return Result.ok(storeService.pageAdminStores(current, size, search));
    }
}
