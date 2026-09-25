package com.wuling.finance.controller;

import com.wuling.security.CurrentUser;
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
    private final com.wuling.common.audit.AuditLogService auditLog;

    public WithdrawalController(WithdrawalService withdrawalService,
                               com.wuling.common.audit.AuditLogService auditLog) {
        this.withdrawalService = withdrawalService;
        this.auditLog = auditLog;
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
        Withdrawal before = withdrawalService.getById(id);
        Withdrawal after = withdrawalService.review(id, approve, reason);
        auditLog.recordChange("ADMIN", "WITHDRAW", approve ? "APPROVE" : "REJECT",
                after.getWithdrawNo(),
                before == null ? null : before.getStatus(), after.getStatus(), reason, null);
        return Result.ok(after);
    }

    /** 后台：标记出款失败（自动解冻） */
    @PostMapping("/api/v1/admin/finance/withdrawals/{id}/fail")
    public Result<Withdrawal> fail(@PathVariable Long id, @RequestParam(required = false) String reason) {
        Withdrawal before = withdrawalService.getById(id);
        Withdrawal after = withdrawalService.markFailed(id, reason);
        auditLog.recordChange("ADMIN", "WITHDRAW", "MARK_FAILED", after.getWithdrawNo(),
                before == null ? null : before.getStatus(), after.getStatus(), reason, null);
        return Result.ok(after);
    }

/**
     * 后台：代经营方发起提现（人工代操作场景）。
     *
     * <p>与小程序端的 {@code POST /api/v1/app/withdrawals} 区别：后者从 JWT 取申请人
     * （{@code CurrentUser.require()}），而本接口申请人由**管理员在后台指定**，
     * 用于「经营方线下申请、运营代为录入」的运营场景。
     *
     * <p>复用 {@link WithdrawalService#apply} 的完整闭环：
     * 余额原子冻结 → 小额即时到账 / 大额进入审核 → 写资金流水。
     *
     * @param userId    发起人（经营方绑定的 app_user.id）
     * @param subjectId 主体 id
     * @param roleType  角色类型（STORE / INVESTOR / CHANNEL / SUPPLIER）
     * @param amount    提现金额，单位：分
     */
    @PostMapping("/api/v1/admin/finance/withdrawals/apply")
    public Result<Withdrawal> adminApply(@RequestParam Long userId,
                                         @RequestParam Long subjectId,
                                         @RequestParam String roleType,
                                         @RequestParam @Min(1) Long amount) {
        Withdrawal created = withdrawalService.apply(userId, subjectId, roleType, amount);
        auditLog.recordChange("ADMIN", "WITHDRAW", "ADMIN_APPLY",
                created.getWithdrawNo(), null, created.getStatus(),
                com.wuling.security.AdminUser.getUsername() == null ? "后台代发起提现" : "后台代发起提现：" + com.wuling.security.AdminUser.getUsername(), null);
        return Result.ok(created);
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

