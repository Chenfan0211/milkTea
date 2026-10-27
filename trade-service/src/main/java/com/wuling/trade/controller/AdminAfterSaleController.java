package com.wuling.trade.controller;

import com.wuling.common.api.PageResult;
import com.wuling.common.api.Result;
import com.wuling.common.audit.AuditLogService;
import com.wuling.security.AdminUser;
import com.wuling.trade.dto.AfterSaleReviewRequest;
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

/** 后台：商城售后审核与查询。 */
@RestController
@RequestMapping("/api/v1/admin/trade")
public class AdminAfterSaleController {

    private final MallAfterSaleService afterSaleService;
    private final AuditLogService auditLog;

    public AdminAfterSaleController(MallAfterSaleService afterSaleService, AuditLogService auditLog) {
        this.afterSaleService = afterSaleService;
        this.auditLog = auditLog;
    }

    @GetMapping("/after-sales")
    public Result<PageResult<MallAfterSaleVO>> list(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search) {
        return Result.ok(afterSaleService.listForAdmin(current, size, status, search));
    }

    @GetMapping("/after-sales/{afterSaleNo}")
    public Result<MallAfterSaleVO> detail(@PathVariable String afterSaleNo) {
        return Result.ok(afterSaleService.detail(null, afterSaleNo));
    }

    @PostMapping("/after-sales/{afterSaleNo}/approve")
    public Result<MallAfterSaleVO> approve(@PathVariable String afterSaleNo,
                                           @Valid @RequestBody(required = false) AfterSaleReviewRequest request) {
        String operator = adminName();
        String remark = request == null ? null : request.getRemark();
        MallAfterSaleVO result = afterSaleService.approve(afterSaleNo, operator, remark);
        auditLog.record(operator, "MALL_AFTER_SALE", "APPROVE", afterSaleNo, "审核通过", null);
        return Result.ok(result);
    }

    @PostMapping("/after-sales/{afterSaleNo}/reject")
    public Result<MallAfterSaleVO> reject(@PathVariable String afterSaleNo,
                                          @Valid @RequestBody(required = false) AfterSaleReviewRequest request) {
        String operator = adminName();
        String remark = request == null ? null : request.getRemark();
        MallAfterSaleVO result = afterSaleService.reject(afterSaleNo, operator, remark);
        auditLog.record(operator, "MALL_AFTER_SALE", "REJECT", afterSaleNo, "审核拒绝：" + remark, null);
        return Result.ok(result);
    }

    private String adminName() {
        AdminUser.require();
        String username = AdminUser.getUsername();
        return username == null || username.isBlank() ? String.valueOf(AdminUser.getUserId()) : username;
    }
}
