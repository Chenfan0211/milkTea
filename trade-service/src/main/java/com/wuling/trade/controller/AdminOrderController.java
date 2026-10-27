package com.wuling.trade.controller;

import com.wuling.common.api.PageResult;
import com.wuling.common.api.Result;
import com.wuling.security.AdminUser;
import com.wuling.trade.dto.OrderDTO;
import com.wuling.trade.service.OrderService;
import com.wuling.trade.service.MallOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 后台：订单列表 / 详情 */
@RestController
@RequestMapping("/api/v1/admin/trade")
public class AdminOrderController {

    private final OrderService orderService;
    private final MallOrderService mallOrderService;
    private final com.wuling.common.audit.AuditLogService auditLog;

    public AdminOrderController(OrderService orderService, MallOrderService mallOrderService,
                                com.wuling.common.audit.AuditLogService auditLog) {
        this.orderService = orderService;
        this.mallOrderService = mallOrderService;
        this.auditLog = auditLog;
    }

    @GetMapping("/orders")
    public Result<PageResult<OrderDTO>> orders(@RequestParam(defaultValue = "1") long current,
                                               @RequestParam(defaultValue = "10") long size,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String search,
                                               @RequestParam(required = false) Long storeSubjectId,
                                               @RequestParam(required = false) String startTime,
                                               @RequestParam(required = false) String endTime,
                                               @RequestParam(required = false) String orderType) {
        return Result.ok(orderService.pageOrders(current, size, status, search, storeSubjectId,
                startTime, endTime, orderType));
    }

    @PostMapping("/orders/{orderNo}/ship")
    public Result<OrderDTO> ship(@PathVariable String orderNo, @RequestBody java.util.Map<String, String> body) {
        String company = body == null ? null : body.get("courierCompany");
        String trackingNo = body == null ? null : body.get("trackingNo");
        String operator = String.valueOf(AdminUser.require());
        OrderDTO order = mallOrderService.ship(orderNo, company, trackingNo, operator);
        auditLog.record(operator, "MALL_ORDER", "SHIP", orderNo,
                "快递公司=" + company + " 快递单号=" + trackingNo, null);
        return Result.ok(order);
    }

    /** 后台：订单详情（含商品明细 items） */
    @GetMapping("/orders/{orderNo}")
    public Result<OrderDTO> detail(@PathVariable String orderNo) {
        return Result.ok(orderService.getByOrderNo(orderNo));
    }
}
