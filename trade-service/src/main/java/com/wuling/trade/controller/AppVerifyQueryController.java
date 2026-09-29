package com.wuling.trade.controller;

import com.wuling.common.api.Result;
import com.wuling.security.CurrentUser;
import com.wuling.trade.dto.VerifyRequest;
import com.wuling.trade.entity.VerifyRecord;
import com.wuling.trade.port.StoreOperatorPort;
import com.wuling.trade.service.VerifyService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 小程序端：门店核销。
 *
 * <p>与管理端 {@link VerifyController}（{@code /api/v1/admin/trade}）的区别：
 * 本接口供小程序经营角色（门店工作台）自助核销，鉴权基于小程序 JWT，
 * 并对「调用者是否经营该门店」做服务端强校验。
 *
 * <p><b>越权防护</b>：{@code storeSubjectId} 由前端传入，但服务端会通过
 * {@link StoreOperatorPort} 校验当前登录用户（取自 JWT）确实经营该门店；
 * 校验不通过一律拒绝，且内网异常时按拒绝处理（fail-closed）。
 * 仅靠前端传参就放行，会让任意登录用户核销他人门店订单。
 *
 * <p><b>为什么核销写操作放在这里而不复用管理端</b>：管理端接口要求运营账号
 * 身份，门店店员无法登录运营后台；若把管理端接口直接暴露给小程序，
 * 则等于放弃后台账号体系。因此在小程序侧单开一个受门店归属约束的入口。
 */
@RestController
@RequestMapping("/api/v1/app/workbench")
public class AppVerifyQueryController {

    private final VerifyService verifyService;
    private final StoreOperatorPort storeOperatorPort;

    public AppVerifyQueryController(VerifyService verifyService,
                                    StoreOperatorPort storeOperatorPort) {
        this.verifyService = verifyService;
        this.storeOperatorPort = storeOperatorPort;
    }

    /** 门店核销记录（按门店主体过滤）。 */
    @GetMapping("/store/{storeSubjectId}/verify-records")
    public Result<List<VerifyRecord>> verifyRecords(@PathVariable Long storeSubjectId) {
        return Result.ok(verifyService.records(storeSubjectId));
    }

    /** 待核销池（门店维度，供核销页展示）。 */
    @GetMapping("/store/{storeSubjectId}/verify-pool")
    public Result<List<?>> verifyPool(@PathVariable Long storeSubjectId) {
        return Result.ok(verifyService.pendingPool(storeSubjectId));
    }

    /**
     * 执行门店核销（扫码 / 输码）。
     *
     * <p>核销后订单置 COMPLETED 并异步触发五方分账（见 {@code VerifyService}）。
     * 重复核销、非已支付订单会被服务拒绝，前端只需展示后端返回的 message。
     *
     * @param storeSubjectId 门店主体 ID（须为当前用户经营的门店）
     * @param request        核销请求：{@code code} 取餐码或订单号
     */
    @PostMapping("/store/{storeSubjectId}/verify")
    public Result<VerifyService.VerifyResult> verify(
            @PathVariable Long storeSubjectId,
            @Valid @RequestBody VerifyRequest request) {

        Long userId = CurrentUser.require();
        // 越权防护：必须确认当前用户经营该门店，否则拒绝
        if (!storeOperatorPort.isStoreOperator(userId, storeSubjectId)) {
            throw new com.wuling.common.exception.BusinessException(
                    com.wuling.common.api.ResultCode.FORBIDDEN,
                    "无权核销该门店订单，请确认已开通门店经营角色");
        }

        // 强制按订单核销，点单核销仅允许 ORDER 类型
        request.setType("ORDER");
        // 操作人记录为当前登录用户，不接受前端传入的操作人标识
        request.setOperator("mini-user-" + userId);

        return Result.ok(verifyService.verify(request));
    }

    /**
     * 执行兑换核销（扫码 / 输码核销自提码）。
     *
     * <p>兑换单（ExchangeOrder）归属营销域，自提码不绑定具体门店
     * （exchange_order 无 store_subject_id），因此不做「该兑换单属于本店」的
     * 归属校验；但仍要求调用者是门店经营角色，避免任意登录用户核销兑换码。
     *
     * <p>核销结果：写 verify_record(type=EXCHANGE) 并发布 EXCHANGE_VERIFIED
     * 事件，由营销域异步把兑换单置 VERIFIED（最终一致，见 ExchangeVerifyConsumer）。
     *
     * @param storeSubjectId 门店主体 ID（用于校验当前用户经营门店）
     * @param request        核销请求：{@code code} 兑换自提码
     */
    @PostMapping("/store/{storeSubjectId}/verify-exchange")
    public Result<VerifyService.VerifyResult> verifyExchange(
            @PathVariable Long storeSubjectId,
            @Valid @RequestBody VerifyRequest request) {

        Long userId = CurrentUser.require();
        // 仍校验门店经营归属：兑换核销也必须由门店店员执行
        if (!storeOperatorPort.isStoreOperator(userId, storeSubjectId)) {
            throw new com.wuling.common.exception.BusinessException(
                    com.wuling.common.api.ResultCode.FORBIDDEN,
                    "无权核销该门店订单，请确认已开通门店经营角色");
        }

        request.setType("EXCHANGE");
        request.setOperator("mini-user-" + userId);

        return Result.ok(verifyService.verify(request));
    }

    /** 当前用户可核销的门店（供核销页选择/校验，返回主体 ID 列表）。 */
    @GetMapping("/store-operator/check")
    public Result<Map<String, Object>> storeOperatorCheck(
            @org.springframework.web.bind.annotation.RequestParam Long subjectId) {
        Long userId = CurrentUser.require();
        boolean allowed = storeOperatorPort.isStoreOperator(userId, subjectId);
        return Result.ok(Map.of("allowed", allowed));
    }
}