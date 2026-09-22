package com.wuling.trade.controller;

import com.wuling.common.api.Result;
import com.wuling.trade.dto.OrderDTO;
import com.wuling.trade.dto.VerifyRequest;
import com.wuling.trade.entity.VerifyRecord;
import com.wuling.trade.service.VerifyService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 门店核销：核销池 / 执行核销 / 核销记录 */
@RestController
@RequestMapping("/api/v1/admin/trade")
public class VerifyController {

    private final VerifyService verifyService;

    public VerifyController(VerifyService verifyService) {
        this.verifyService = verifyService;
    }

    @GetMapping("/verify-pool")
    public Result<List<OrderDTO>> pool(@RequestParam(required = false) Long storeSubjectId) {
        return Result.ok(verifyService.pendingPool(storeSubjectId));
    }

    @PostMapping("/verify")
    public Result<VerifyService.VerifyResult> verify(@Valid @RequestBody VerifyRequest request) {
        return Result.ok(verifyService.verify(request));
    }

    @GetMapping("/verify-records")
    public Result<List<VerifyRecord>> records(@RequestParam(required = false) Long storeSubjectId) {
        return Result.ok(verifyService.records(storeSubjectId));
    }
}
