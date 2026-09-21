package com.wuling.finance.controller;

import com.wuling.common.api.Result;
import com.wuling.finance.entity.SubjectAccount;
import com.wuling.finance.service.LedgerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** 后台：分账账户 / 结算 */
@RestController
@RequestMapping("/api/v1/admin/finance")
public class FinanceController {

    private final LedgerService ledgerService;

    public FinanceController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @GetMapping("/accounts")
    public Result<List<SubjectAccount>> accounts(@RequestParam List<Long> subjectIds) {
        return Result.ok(ledgerService.accountsOf(subjectIds));
    }

    /** 手动触发 T+1 结算（生产由定时任务调用） */
    @PostMapping("/settle")
    public Result<Integer> settle(@RequestParam(required = false) String date) {
        LocalDate settleDate = date == null ? LocalDate.now() : LocalDate.parse(date);
        return Result.ok(ledgerService.settleDue(settleDate));
    }
}

