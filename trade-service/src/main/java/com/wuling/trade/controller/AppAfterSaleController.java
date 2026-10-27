package com.wuling.trade.controller;

import com.wuling.common.api.PageResult;
import com.wuling.common.api.Result;
import com.wuling.security.CurrentUser;
import com.wuling.trade.dto.CreateAfterSaleRequest;
import com.wuling.trade.dto.MallAfterSaleVO;
import com.wuling.trade.service.MallAfterSaleService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 小程序：商城售后申请与查询。 */
@RestController
@RequestMapping("/api/v1/app/mall")
public class AppAfterSaleController {

    private final MallAfterSaleService afterSaleService;

    public AppAfterSaleController(MallAfterSaleService afterSaleService) {
        this.afterSaleService = afterSaleService;
    }

    @PostMapping("/orders/{orderNo}/after-sales")
    public Result<MallAfterSaleVO> create(@PathVariable String orderNo,
                                          @Valid @RequestBody CreateAfterSaleRequest request) {
        return Result.ok(afterSaleService.create(CurrentUser.require(), orderNo, request));
    }

    @GetMapping("/orders/{orderNo}/after-sales")
    public Result<List<MallAfterSaleVO>> listByOrder(@PathVariable String orderNo) {
        return Result.ok(afterSaleService.listByOrder(CurrentUser.require(), orderNo));
    }

    @GetMapping("/after-sales")
    public Result<PageResult<MallAfterSaleVO>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return Result.ok(afterSaleService.listByUser(CurrentUser.require(), page, size));
    }

    @GetMapping("/after-sales/{afterSaleNo}")
    public Result<MallAfterSaleVO> detail(@PathVariable String afterSaleNo) {
        return Result.ok(afterSaleService.detail(CurrentUser.require(), afterSaleNo));
    }
}
