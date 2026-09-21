package com.wuling.trade.controller;

import com.wuling.common.api.PageResult;
import com.wuling.common.api.Result;
import com.wuling.trade.dto.OrderDTO;
import com.wuling.trade.service.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 后台：订单列表 */
@RestController
@RequestMapping("/api/v1/admin/trade")
public class AdminOrderController {

    private final OrderService orderService;

    public AdminOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/orders")
    public Result<PageResult<OrderDTO>> orders(@RequestParam(defaultValue = "1") long current,
                                               @RequestParam(defaultValue = "10") long size,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String search) {
        return Result.ok(orderService.pageOrders(current, size, status, search));
    }
}
