package com.wuling.subject.onboard;

import com.wuling.common.api.PageResult;
import com.wuling.common.api.Result;
import com.wuling.security.CurrentUser;
import com.wuling.subject.onboard.dto.MerchantOnboardRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 经营者入驻与签约（小程序端 + 运营后台）。
 *
 * <p>「入驻」是易宝子商户入网，「签约」是审核通过后在易宝页面完成电子签章。
 * 两者串行，完成后才能提现。
 */
@RestController
public class MerchantOnboardController {

    private final MerchantOnboardService onboardService;
    private final com.wuling.subject.service.StoreOperatorService storeOperatorService;

    public MerchantOnboardController(MerchantOnboardService onboardService,
                                     com.wuling.subject.service.StoreOperatorService storeOperatorService) {
        this.onboardService = onboardService;
        this.storeOperatorService = storeOperatorService;
    }

    /** 小程序：提交入驻申请 */
    @PostMapping("/api/v1/app/merchant/onboard")
    public Result<MerchantOnboard> submit(@RequestParam Long subjectId,
                                          @RequestBody MerchantOnboardRequest request) {
        requireSubjectOperator(subjectId);
        MerchantOnboard onboard = onboardService.submit(subjectId, CurrentUser.require(), request);
        return Result.ok(onboard);
    }

    /** 小程序：查询入驻状态 + 签署地址 */
    @GetMapping("/api/v1/app/merchant/onboard")
    public Result<Map<String, Object>> status(@RequestParam Long subjectId) {
        requireSubjectOperator(subjectId);
        MerchantOnboard onboard = onboardService.findBySubject(subjectId);
        Map<String, Object> data = new LinkedHashMap<>();
        if (onboard == null) {
            data.put("onboarded", false);
            data.put("status", null);
            data.put("message", "尚未提交入驻申请");
            return Result.ok(data);
        }
        data.put("onboarded", onboardService.isOnboarded(subjectId));
        data.put("status", onboard.getApplicationStatus());
        data.put("signType", onboard.getSignType());
        data.put("signUrl", onboard.getSignUrl());
        data.put("signed", onboard.getSignedTime() != null);
        data.put("rejectReason", onboard.getRejectReason());
        data.put("yeepayMerchantNo", onboard.getYeepayMerchantNo());
        data.put("message", statusMessage(onboard));
        return Result.ok(data);
    }

    /** 小程序：主动刷新进度（补偿回调丢失） */
    @PostMapping("/api/v1/app/merchant/onboard/refresh")
    public Result<MerchantOnboard> refresh(@RequestParam Long subjectId) {
        requireSubjectOperator(subjectId);
        return Result.ok(onboardService.refresh(subjectId));
    }

    /** 小程序：告知「已在易宝页面完成签署」 */
    @PostMapping("/api/v1/app/merchant/onboard/signed")
    public Result<Void> markSigned(@RequestParam Long subjectId) {
        requireSubjectOperator(subjectId);
        onboardService.markSigned(subjectId);
        return Result.ok(null);
    }

    /** 后台：入驻列表 */
    @GetMapping("/api/v1/admin/subjects/onboards")
    public Result<PageResult<MerchantOnboard>> adminList(@RequestParam(defaultValue = "1") long current,
                                                         @RequestParam(defaultValue = "10") long size,
                                                         @RequestParam(required = false) String status) {
        return Result.ok(onboardService.page(current, size, status));
    }

    /** 后台：查看某主体的入驻资料（证件与手机号返回掩码） */
    @GetMapping("/api/v1/admin/subjects/onboards/material")
    public Result<MerchantMaterial> adminMaterial(@RequestParam Long subjectId) {
        return Result.ok(onboardService.findMaterial(subjectId));
    }

    /**
     * 校验当前登录用户有权操作该主体。
     *
     * <p><b>为什么必须校验</b>：subjectId 来自 query 参数，可被任意篡改。
     * 入驻资料含证件号与手机号，若不校验，攻击者能提交他人主体的入驻
     * 或读取其资料掩码，属越权与个人信息泄露。
     *
     * <p>复用 {@code StoreOperatorService.canOperate}：其实现是
     * 「user_role_grant 有该主体有效授权 OR app_user.bound_subject_id 匹配」，
     * 对 STORE / INVESTOR / CHANNEL / SUPPLIER / LEADER 全部适用。
     */
    private void requireSubjectOperator(Long subjectId) {
        Long userId = CurrentUser.require();
        if (subjectId == null || !storeOperatorService.canOperate(userId, subjectId)) {
            throw new com.wuling.common.exception.BusinessException(
                    com.wuling.common.api.ResultCode.FORBIDDEN, "无权操作该主体的入驻信息");
        }
    }

    private String statusMessage(MerchantOnboard onboard) {
        String status = onboard.getApplicationStatus();
        if (status == null) {
            return "待提交";
        }
        return switch (status) {
            case MerchantOnboard.STATUS_REVIEWING -> "资料审核中，通常 1-3 个工作日";
            case MerchantOnboard.STATUS_REVIEW_BACK -> "审核未通过：" + onboard.getRejectReason();
            case MerchantOnboard.STATUS_BUSINESS_OPENING -> "审核通过，业务开通中";
            case MerchantOnboard.STATUS_COMPLETED -> "已完成，可正常提现";
            default -> status;
        };
    }
}
