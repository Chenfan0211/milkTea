package com.wuling.subject.onboard;

import com.wuling.finance.divide.OrderDivideService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 易宝通知落库接口（供 trade-service 解密后转发）。
 *
 * <p>{@code /internal/**} 不经网关路由，各服务仅监听 127.0.0.1。
 */
@RestController
@RequestMapping("/internal/yeepay")
public class YeepayNotifyInternalController {

    private static final Logger log = LoggerFactory.getLogger(YeepayNotifyInternalController.class);

    private final MerchantOnboardService onboardService;
    private final OrderDivideService divideService;

    public YeepayNotifyInternalController(MerchantOnboardService onboardService,
                                          OrderDivideService divideService) {
        this.onboardService = onboardService;
        this.divideService = divideService;
    }

    /**
     * 入网结果通知落库。
     *
     * <p>状态到达 COMPLETED 时触发「补分账」：该主体此前因未入驻而挂在
     * {@code PENDING_ONBOARD} 的结算记录，此时可以分出去了。
     */
    @PostMapping("/onboard-notify")
    public Map<String, Object> onboardNotify(@RequestBody Map<String, Object> body) {
        String requestNo = str(body, "requestNo");
        String status = str(body, "applicationStatus");
        String merchantNo = str(body, "merchantNo");
        String signUrl = str(body, "signUrl");
        String rejectReason = str(body, "rejectReason");
        String raw = str(body, "raw");
        log.info("收到易宝入网通知 requestNo={} status={} merchantNo={}", requestNo, status, merchantNo);
        onboardService.applyNotify(requestNo, status, merchantNo, signUrl, rejectReason, raw);

        if (MerchantOnboard.STATUS_COMPLETED.equals(status)) {
            // 入驻完成 → 补分账（异步，失败由定时任务兜底）
            try {
                MerchantOnboard onboard = onboardService.findBySubjectIdByRequestNo(requestNo);
                if (onboard != null) {
                    divideService.catchUpDivides(onboard.getSubjectId());
                }
            } catch (Exception e) {
                log.warn("入驻完成补分账触发失败 requestNo={} err={}", requestNo, e.getMessage());
            }
        }
        return Map.of("ok", true);
    }

    /**
     * 易宝清算结果通知落库。
     *
     * <p>易宝要求「清算成功后才能分账」，核销早于清算时会被门禁拦下
     * （标记 {@code PENDING_CLEARING}）。本接口收到清算通知后
     * 置清算成功并立即触发该订单的分账。
     *
     * <p><b>幂等</b>：易宝最多重试 9 次通知，重复调用安全。
     */
    @PostMapping("/clearing-notify")
    public Map<String, Object> clearingNotify(@RequestBody Map<String, Object> body) {
        String orderNo = str(body, "orderNo");
        String status = str(body, "status");
        String settleAmount = str(body, "ypSettleAmount");
        log.info("收到易宝清算通知 orderNo={} status={} ypSettleAmount={}",
                orderNo, status, settleAmount);
        if ("SUCCESS".equalsIgnoreCase(status)) {
            try {
                divideService.onClearingSuccess(orderNo);
            } catch (Exception e) {
                // 分账失败不影响清算状态落库，由 DivideRetryJob 兜底
                log.warn("清算后触发分账失败 orderNo={} err={}", orderNo, e.getMessage());
            }
        }
        return Map.of("ok", true);
    }

    private String str(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
