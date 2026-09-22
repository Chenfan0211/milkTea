package com.wuling.finance.controller;

import com.wuling.common.api.Result;
import com.wuling.finance.entity.ReconcileIssue;
import com.wuling.finance.service.ReconcileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 资金对账管理接口（第 8 期）。
 *
 * <p>供后台查看与处理对账异常。路径在 {@code /api/v1/admin/finance/**} 下，
 * 由网关统一鉴权。
 */
@RestController
@RequestMapping("/api/v1/admin/finance/reconcile")
public class ReconcileController {

    private final ReconcileService reconcileService;

    public ReconcileController(ReconcileService reconcileService) {
        this.reconcileService = reconcileService;
    }

    /** 待处理的对账异常 */
    @GetMapping("/issues")
    public Result<List<ReconcileIssue>> issues() {
        return Result.ok(reconcileService.openIssues());
    }

    /** 手动触发一次对账（排查/验证用） */
    @PostMapping("/run")
    public Result<Map<String, Object>> run() {
        int found = reconcileService.reconcile();
        return Result.ok(Map.of("newIssues", found));
    }

    /**
     * 处理异常。
     *
     * @param status RESOLVED（已处理）或 IGNORED（已忽略）
     */
    @PostMapping("/issues/{id}/resolve")
    public Result<ReconcileIssue> resolve(@PathVariable Long id, @RequestParam String status) {
        return Result.ok(reconcileService.resolve(id, status));
    }
}
