package com.wuling.system.controller;

import com.wuling.common.api.Result;
import com.wuling.system.dto.StoreTypeDTO;
import com.wuling.system.service.DictService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/app")
public class StoreTypeController {

    private final DictService dictService;

    public StoreTypeController(DictService dictService) {
        this.dictService = dictService;
    }

    @GetMapping("/store-types")
    public Result<List<StoreTypeDTO>> storeTypes() {
        return Result.ok(dictService.listStoreTypes());
    }
}
