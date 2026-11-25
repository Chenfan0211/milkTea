package com.wuling.finance.payee;

import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 经营者结算卡（小程序端）。
 *
 * <p><b>响应只含掩码</b>：卡号明文绝不回传给前端。
 * 若要展示完整卡号，应由持卡人重新输入并单独鉴权，本项目暂无此需求。
 */
@RestController
public class PayoutAccountController {

    private final PayoutAccountService accountService;
    private final com.wuling.subject.service.StoreOperatorService storeOperatorService;

    public PayoutAccountController(PayoutAccountService accountService,
                                   com.wuling.subject.service.StoreOperatorService storeOperatorService) {
        this.accountService = accountService;
        this.storeOperatorService = storeOperatorService;
    }

    /** 我的结算卡列表 */
    @GetMapping("/api/v1/app/payout-accounts")
    public Result<List<Map<String, Object>>> list(@RequestParam Long subjectId) {
        requireSubjectOperator(subjectId);
        List<Map<String, Object>> data = new ArrayList<>();
        for (PayoutAccount account : accountService.list(subjectId)) {
            data.add(toView(account));
        }
        return Result.ok(data);
    }

    /** 绑定结算卡 */
    @PostMapping("/api/v1/app/payout-accounts")
    public Result<Map<String, Object>> bind(@RequestParam Long subjectId,
                                            @RequestBody com.wuling.finance.payee.dto.BindCardRequest input) {
        requireSubjectOperator(subjectId);
        PayoutAccount account = accountService.bind(subjectId, CurrentUser.require(), input);
        return Result.ok(toView(account));
    }

    /** 设为默认结算卡 */
    @PostMapping("/api/v1/app/payout-accounts/{id}/default")
    public Result<Void> setDefault(@RequestParam Long subjectId, @PathVariable Long id) {
        requireSubjectOperator(subjectId);
        accountService.setDefault(subjectId, id);
        return Result.ok(null);
    }

    /**
     * 校验当前登录用户有权操作该主体。
     *
     * <p><b>为什么必须校验</b>：subjectId 来自 query 参数，前端可任意篡改。
     * 若不校验，任何人都能查他人结算卡掩码、或给他人主体绑卡——
     * 后者会让别人提现时打到攻击者的卡上，属资金安全事故。
     *
     * <p>复用 {@code StoreOperatorService.canOperate}：它的实现是
     * 「user_role_grant 有该主体有效授权 OR app_user.bound_subject_id 匹配」，
     * 对 STORE / INVESTOR / CHANNEL / SUPPLIER / LEADER 全部适用，无需另建校验器。
     */
    private void requireSubjectOperator(Long subjectId) {
        Long userId = CurrentUser.require();
        if (subjectId == null || !storeOperatorService.canOperate(userId, subjectId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该主体的结算卡");
        }
    }

    /**
     * 转视图：只暴露掩码，不暴露密文列。
     *
     * <p>为什么不直接返回实体：实体含 {@code bankCardNoEnc}，
     * 虽然加密但没必要出现在网络响应里，减少泄露面。
     */
    private Map<String, Object> toView(PayoutAccount account) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", account.getId());
        view.put("accountType", account.getAccountType());
        view.put("accountName", account.getAccountName());
        view.put("cardMask", account.getBankCardMask());
        view.put("bankName", account.getBankName());
        view.put("isDefault", account.getIsDefault());
        view.put("verifyStatus", account.getVerifyStatus());
        view.put("verifyMsg", account.getVerifyMsg());
        return view;
    }
}
