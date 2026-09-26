package com.wuling.trade.controller;

import com.wuling.common.api.Result;
import com.wuling.trade.dto.OrderDTO;
import com.wuling.trade.dto.RefundRequest;
import com.wuling.trade.service.RefundService;
import org.springframework.web.bind.annotation.*;

/** 后台：整单退款 */
@RestController
@RequestMapping("/api/v1/admin/trade")
public class RefundController {

    private final RefundService refundService;

    public RefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    @PostMapping("/refund")
    public Result<OrderDTO> refund(@RequestParam String orderNo, @RequestBody(required = false) RefundRequest request) {
        String reason = request == null ? null : request.getReason();
        return Result.ok(refundService.refund(orderNo, reason));
    }

    /** 重新退款：仅退款失败(FAILED)的单据可重试 */
    @PostMapping("/refunds/{id}/retry")
    public Result<OrderDTO> retry(@PathVariable Long id) {
        return Result.ok(refundService.retry(id));
    }
}