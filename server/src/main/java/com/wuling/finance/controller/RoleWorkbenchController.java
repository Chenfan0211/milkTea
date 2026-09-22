package com.wuling.finance.controller;

import com.wuling.common.api.Result;
import com.wuling.finance.entity.FundFlow;
import com.wuling.finance.entity.SettlementRecord;
import com.wuling.finance.service.RoleWorkbenchService;


import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 小程序端：四类角色工作台（数据按主体隔离）。
 * 路径统一为 /subject/{subjectId}/...，避免与角色前缀产生路由歧义。
 */
@RestController
@RequestMapping("/api/v1/app/workbench")
public class RoleWorkbenchController {

    private final RoleWorkbenchService workbenchService;

    public RoleWorkbenchController(RoleWorkbenchService workbenchService) {
        this.workbenchService = workbenchService;
    }

    // ---------- 通用（任意主体） ----------

    @GetMapping("/subject/{subjectId}/overview")
    public Result<RoleWorkbenchService.Overview> overview(@PathVariable Long subjectId) {
        return Result.ok(workbenchService.overview(subjectId));
    }

    @GetMapping("/subject/{subjectId}/flows")
    public Result<List<FundFlow>> flows(@PathVariable Long subjectId) {
        return Result.ok(workbenchService.flows(subjectId));
    }

    @GetMapping("/subject/{subjectId}/settlements")
    public Result<List<SettlementRecord>> settlements(@PathVariable Long subjectId) {
        return Result.ok(workbenchService.settlements(subjectId));
    }

    // ---------- 门店 ----------

    @GetMapping("/store/{storeSubjectId}/orders")
    public Result<List<Map<String, Object>>> storeOrders(@PathVariable Long storeSubjectId) {
        return Result.ok(workbenchService.storeOrders(storeSubjectId));
    }

    // ---------- 渠道 ----------

    @GetMapping("/channel/{channelSubjectId}/stores")
    public Result<List<Map<String, Object>>> channelStores(@PathVariable Long channelSubjectId) {
        return Result.ok(workbenchService.channelStores(channelSubjectId));
    }

    @GetMapping("/channel/{channelSubjectId}/orders")
    public Result<List<Map<String, Object>>> channelOrders(@PathVariable Long channelSubjectId) {
        return Result.ok(workbenchService.channelOrders(channelSubjectId));
    }

    // ---------- 投资人 ----------

    @GetMapping("/investor/{investorSubjectId}/stores")
    public Result<List<Map<String, Object>>> investorStores(@PathVariable Long investorSubjectId) {
        return Result.ok(workbenchService.investorStores(investorSubjectId));
    }
}
