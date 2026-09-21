package com.wuling.finance.controller;

import com.wuling.auth.security.CurrentUser;
import com.wuling.common.api.PageResult;
import com.wuling.common.api.Result;
import com.wuling.finance.entity.Withdrawal;
import com.wuling.finance.service.WithdrawalService;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 提现：申请 / 审核 / 失败解冻 */
@RestController
public class WithdrawalController {

    private final WithdrawalService withdrawalService;

    public WithdrawalController(WithdrawalService withdrawalService) {
        this.withdrawalService = withdrawalService;
    }

    /** 小程序端：申请提现 */
    /** 申请提现（申请人取自 JWT） */
    @PostMapping("/api/v1/app/withdrawals")
    public Result<Withdrawal> apply(@RequestParam Long subjectId,
                                    @RequestParam String roleType,
                                    @RequestParam @Min(1) Long amount) {
        return Result.ok(withdrawalService.apply(CurrentUser.require(), subjectId, roleType, amount));
    }

    /** 小程序端：我的提现记录 */
    @GetMapping("/api/v1/app/withdrawals")
    public Result<PageResult<Withdrawal>> myList(@RequestParam(defaultValue = "1") long current,
                                                 @RequestParam(defaultValue = "10") long size,
                                                 @RequestParam(required = false) String status) {
        return Result.ok(withdrawalService.page(current, size, status));
    }

    /** 后台：提现列表 */
    @GetMapping("/api/v1/admin/finance/withdrawals")
    public Result<PageResult<Withdrawal>> adminList(@RequestParam(defaultValue = "1") long current,
                                                    @RequestParam(defaultValue = "10") long size,
                                                    @RequestParam(required = false) String status) {
        return Result.ok(withdrawalService.page(current, size, status));
    }

    /** 后台：审核 */
    @PostMapping("/api/v1/admin/finance/withdrawals/{id}/review")
    public Result<Withdrawal> review(@PathVariable Long id,
                                     @RequestParam boolean approve,
                                     @RequestParam(required = false) String reason) {
        return Result.ok(withdrawalService.review(id, approve, reason));
    }

    /** 后台：标记出款失败（自动解冻） */
    @PostMapping("/api/v1/admin/finance/withdrawals/{id}/fail")
    public Result<Withdrawal> fail(@PathVariable Long id, @RequestParam(required = false) String reason) {
        return Result.ok(withdrawalService.markFailed(id, reason));
    }

    /** 小额即时额度说明 */
    @GetMapping("/api/v1/app/withdrawals/rule")
    public Result<Map<String, Object>> rule() {
        return Result.ok(Map.of(
                "instantLimit", WithdrawalService.INSTANT_LIMIT,
                "instantNote", "单笔不超过该额度小额即时到账，无需人工审核",
                "auditNote", "超过即时额度需后台审核，审核通过后出款",
                "failureNote", "失败或驳回将自动解冻对应金额"));
    }
}

