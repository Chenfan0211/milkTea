package com.wuling.finance.deposit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wuling.common.api.PageResult;
import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import com.wuling.common.audit.AuditLogService;
import com.wuling.common.exception.BusinessException;
import com.wuling.finance.entity.DepositRecord;
import com.wuling.finance.entity.SubjectAccount;
import com.wuling.finance.mapper.DepositRecordMapper;
import com.wuling.finance.mapper.SubjectAccountMapper;
import com.wuling.finance.service.LedgerService;
import com.wuling.security.AdminUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 经营者押金（运营后台）。
 *
 * <p><b>用途</b>：押金是退款兜底金。经营者在提现后再发生售后退款时，
 * 可用余额可能不足，此时从押金抵扣，避免退款直接失败。
 *
 * <p><b>不可提现</b>：押金不参与任何提现计算，也不分账到子商户——
 * 钱留在平台主账户，只有「退款抵扣」一条出口。
 *
 * <p><b>审计</b>：每次设定/调整都写 {@code deposit_record} 台账 + {@code audit_log}，
 * 便于事后追溯「谁在什么时候把谁的押金改成了多少」。
 */
@RestController
public class DepositController {

    private final LedgerService ledgerService;
    private final DepositRecordMapper depositRecordMapper;
    private final SubjectAccountMapper subjectAccountMapper;
    private final AuditLogService auditLog;

    public DepositController(LedgerService ledgerService,
                             DepositRecordMapper depositRecordMapper,
                             SubjectAccountMapper subjectAccountMapper,
                             AuditLogService auditLog) {
        this.ledgerService = ledgerService;
        this.depositRecordMapper = depositRecordMapper;
        this.subjectAccountMapper = subjectAccountMapper;
        this.auditLog = auditLog;
    }

    /**
     * 设定/调整押金（目标值语义）。
     *
     * <p>为什么传目标值而不是增量：运营的意图是「这个经营者应该有 500 元押金」，
     * 让运营自己算差额容易出错。系统按差额写台账。
     *
     * @param amount 目标押金（分），必须 >= 0
     */
    @PostMapping("/api/v1/admin/finance/deposits/{subjectId}/set")
    public Result<Map<String, Object>> setDeposit(@PathVariable Long subjectId,
                                                   @RequestParam long amount,
                                                   @RequestParam(required = false) String reason) {
        if (amount < 0L) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "押金不能为负数");
        }
        SubjectAccount before = findAccount(subjectId);
        long beforeDeposit = before == null || before.getDepositBalance() == null
                ? 0L : before.getDepositBalance();

        long after = ledgerService.setDeposit(subjectId, amount,
                AdminUser.getUsername(), reason);

        auditLog.recordChange(
                AdminUser.getUsername() == null ? "ADMIN" : AdminUser.getUsername(),
                "DEPOSIT",
                beforeDeposit == 0L ? "SET" : "ADJUST",
                "subject:" + subjectId,
                String.valueOf(beforeDeposit),
                String.valueOf(after),
                reason,
                null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("subjectId", subjectId);
        data.put("beforeDeposit", beforeDeposit);
        data.put("depositBalance", after);
        return Result.ok(data);
    }

    /** 查询某主体押金与变动明细。 */
    @GetMapping("/api/v1/admin/finance/deposits")
    public Result<Map<String, Object>> detail(@RequestParam Long subjectId,
                                              @RequestParam(defaultValue = "50") long limit) {
        SubjectAccount account = findAccount(subjectId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("subjectId", subjectId);
        data.put("depositBalance", account == null || account.getDepositBalance() == null
                ? 0L : account.getDepositBalance());
        data.put("availableBalance", account == null || account.getAvailableBalance() == null
                ? 0L : account.getAvailableBalance());
        data.put("frozenBalance", account == null || account.getFrozenBalance() == null
                ? 0L : account.getFrozenBalance());

        List<DepositRecord> records = depositRecordMapper.selectList(
                new LambdaQueryWrapper<DepositRecord>()
                        .eq(DepositRecord::getSubjectId, subjectId)
                        .orderByDesc(DepositRecord::getId)
                        .last("limit " + Math.max(1, Math.min(limit, 200))));
        List<Map<String, Object>> items = new ArrayList<>();
        for (DepositRecord record : records) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("recordNo", record.getRecordNo());
            item.put("changeAmount", record.getChangeAmount());
            item.put("balanceAfter", record.getBalanceAfter());
            item.put("bizType", record.getBizType());
            item.put("bizNo", record.getBizNo());
            item.put("orderNo", record.getOrderNo());
            item.put("operator", record.getOperator());
            item.put("reason", record.getReason());
            item.put("createTime", record.getCreateTime());
            items.add(item);
        }
        data.put("records", items);
        return Result.ok(data);
    }

    /** 押金列表（按主体分页，含可用/冻结/押金三列）。 */
    @GetMapping("/api/v1/admin/finance/deposits/page")
    public Result<PageResult<Map<String, Object>>> page(@RequestParam(defaultValue = "1") long current,
                                                        @RequestParam(defaultValue = "10") long size,
                                                        @RequestParam(required = false) Boolean onlyNonZero) {
        LambdaQueryWrapper<SubjectAccount> query = new LambdaQueryWrapper<SubjectAccount>()
                .orderByDesc(SubjectAccount::getDepositBalance)
                .orderByDesc(SubjectAccount::getId);
        if (Boolean.TRUE.equals(onlyNonZero)) {
            query.gt(SubjectAccount::getDepositBalance, 0L);
        }
        Page<SubjectAccount> page = subjectAccountMapper.selectPage(new Page<>(current, size), query);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SubjectAccount account : page.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("subjectId", account.getSubjectId());
            row.put("roleType", account.getRoleType());
            row.put("availableBalance", account.getAvailableBalance());
            row.put("frozenBalance", account.getFrozenBalance());
            row.put("depositBalance", account.getDepositBalance());
            rows.add(row);
        }
        return Result.ok(PageResult.of(rows, page.getCurrent(), page.getSize(), page.getTotal()));
    }

    private SubjectAccount findAccount(Long subjectId) {
        return subjectAccountMapper.selectOne(new LambdaQueryWrapper<SubjectAccount>()
                .eq(SubjectAccount::getSubjectId, subjectId)
                .last("limit 1"));
    }
}
