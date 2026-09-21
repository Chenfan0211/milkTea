package com.wuling.trade.controller;

import com.wuling.auth.security.CurrentUser;
import com.wuling.common.api.Result;
import com.wuling.trade.dto.CreateOrderRequest;
import com.wuling.trade.dto.OrderDTO;
import com.wuling.trade.dto.PayRequest;
import com.wuling.trade.service.OrderService;
import com.wuling.trade.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 小程序端：下单 / 支付 / 订单查询 */
@RestController
@RequestMapping("/api/v1/app")
public class AppOrderController {

    private final OrderService orderService;
    private final PaymentService paymentService;

    public AppOrderController(OrderService orderService, PaymentService paymentService) {
        this.orderService = orderService;
        this.paymentService = paymentService;
    }

    @PostMapping("/orders")
    public Result<OrderDTO> create(@Valid @RequestBody CreateOrderRequest request) {
        // 下单用户一律取自 JWT，忽略请求体中的 userId，防止替他人下单
        request.setUserId(CurrentUser.require());
        return Result.ok(orderService.createOrder(request));
    }

    /** 我的订单（用户取自 JWT） */
    @GetMapping("/orders")
    public Result<List<OrderDTO>> list() {
        Long userId = CurrentUser.require();
        return Result.ok(orderService.pageOrders(1, 50, null, null).getRecords().stream()
                .filter(o -> o.getUserId().equals(userId))
                .toList());
    }

    /** 订单详情：校验归属，禁止查看他人订单 */
    @GetMapping("/orders/{orderNo}")
    public Result<OrderDTO> detail(@PathVariable String orderNo) {
        OrderDTO order = orderService.getByOrderNo(orderNo);
        if (!CurrentUser.require().equals(order.getUserId())) {
            throw new com.wuling.common.exception.BusinessException(
                    com.wuling.common.api.ResultCode.FORBIDDEN, "无权查看该订单");
        }
        return Result.ok(order);
    }

    /** 发起支付（返回支付单，Mock 通道） */
    @PostMapping("/orders/{orderNo}/pay")
    public Result<OrderDTO> pay(@PathVariable String orderNo, @Valid @RequestBody PayRequest request) {
        return Result.ok(paymentService.pay(orderNo, request));
    }

    /** 支付回调（Mock：真实接入后由第三方通道回调调用） */
    @PostMapping("/payments/callback")
    public Result<OrderDTO> callback(@RequestParam String orderNo, @RequestParam String transactionId) {
        return Result.ok(paymentService.handleCallback(orderNo, transactionId));
    }
}

