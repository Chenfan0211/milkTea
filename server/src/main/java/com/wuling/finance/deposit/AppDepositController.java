package com.wuling.finance.deposit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.finance.entity.DepositRecord;
import com.wuling.finance.entity.SubjectAccount;
import com.wuling.finance.mapper.DepositRecordMapper;
import com.wuling.finance.mapper.SubjectAccountMapper;
import com.wuling.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 经营者押金台账（小程序端，只读）。
 *
 * <p><b>为什么需要它</b>：押金被退款抵扣后，经营者只看到余额变小
 * 却查不到原因，必然产生纠纷。本接口把 {@code deposit_record}
 * 的变动明细（含 {@code reason} 中写明的订单号与退款单号）暴露给本人。
 *
 * <p><b>与后台接口的区别</b>：后台（{@code /api/v1/admin/finance/deposits}）
 * 可看全部主体；本接口只能看自己有权经营的主体，且不返回操作人。
 */
@RestController
public class AppDepositController {

    private final DepositRecordMapper depositRecordMapper;
    private final SubjectAccountMapper subjectAccountMapper;
    private final com.wuling.subject.service.StoreOperatorService storeOperatorService;

    public AppDepositController(DepositRecordMapper depositRecordMapper,
                                SubjectAccountMapper subjectAccountMapper,
                                com.wuling.subject.service.StoreOperatorService storeOperatorService) {
        this.depositRecordMapper = depositRecordMapper;
        this.subjectAccountMapper = subjectAccountMapper;
        this.storeOperatorService = storeOperatorService;
    }

    /**
     * 我的押金余额与变动明细。
     *
     * @param subjectId 经营主体 id
     * @param limit     最多返回条数（默认 50，上限 200）
     */
    @GetMapping("/api/v1/app/deposit/records")
    public Result<Map<String, Object>> records(@RequestParam Long subjectId,
                                               @RequestParam(defaultValue = "50") long limit) {
        requireSubjectOperator(subjectId);

        SubjectAccount account = subjectAccountMapper.selectOne(
                new LambdaQueryWrapper<SubjectAccount>()
                        .eq(SubjectAccount::getSubjectId, subjectId)
                        .last("limit 1"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("subjectId", subjectId);
        data.put("depositBalance", account == null || account.getDepositBalance() == null
                ? 0L : account.getDepositBalance());
        // 明确告知前端：押金不可提现，避免用户误以为可以一起提走
        data.put("withdrawable", false);

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
            item.put("orderNo", record.getOrderNo());
            // reason 由后端写入时已包含订单号与退款单号，直接展示给经营者
            item.put("reason", record.getReason());
            item.put("createTime", record.getCreateTime());
            // 刻意不返回 operator：操作人属内部信息，对经营者无意义
            items.add(item);
        }
        data.put("records", items);
        return Result.ok(data);
    }

    /**
     * 校验当前登录用户有权操作该主体。
     *
     * <p>与结算卡/入驻接口同一口径：复用 {@code StoreOperatorService.canOperate}
     * （覆盖 STORE / INVESTOR / CHANNEL / SUPPLIER / LEADER）。
     * 押金余额属资金信息，越权读取会让经营者看到同行的财务数据。
     */
    private void requireSubjectOperator(Long subjectId) {
        Long userId = CurrentUser.require();
        if (subjectId == null || !storeOperatorService.canOperate(userId, subjectId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权查看该主体的押金信息");
        }
    }
}
