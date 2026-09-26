package com.wuling.marketing.internal;

import com.wuling.common.exception.BusinessException;
import com.wuling.marketing.entity.StoredValueOrder;
import com.wuling.marketing.service.StoredValueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 储值订单内部接口（第 15 期储值支付接入）。
 *
 * <p><b>调用方</b>：trade-service 的支付回调链路。
 * 微信回调只带回 {@code out_trade_no}，trade 域需反查储值订单并驱动入账。
 *
 * <p><b>安全约束</b>：{@code /internal/**} 不在网关路由范围内
 * （网关仅路由 {@code /api/v1/**} 与 {@code /auth/**}），
 * 且本服务仅监听内网/本机，因此不会经公网暴露。
 * 生产部署须确保 marketing-service 端口不对外网开放。
 *
 * <p><b>为什么不复用 {@code /api/v1/app/stored-value/**}</b>：
 * 那两个接口面向小程序端，需登录态（{@code MiniAppAuthInterceptor} 按路径拦截），
 * 而回调来自微信服务器，没有用户 JWT。混用会导致回调被鉴权拦截。
 */
@RestController
@RequestMapping("/internal/stored-value-orders")
public class StoredValueInternalController {

    private static final Logger log = LoggerFactory.getLogger(StoredValueInternalController.class);

    private final StoredValueService storedValueService;

    public StoredValueInternalController(StoredValueService storedValueService) {
        this.storedValueService = storedValueService;
    }

    /**
     * 按订单号查询储值订单。
     *
     * <p>返回结构固定含 {@code found} 字段：调用方据此区分「订单不存在」
     * 与「查询失败（抛异常）」，避免把故障当作业务空结果。
     */
    @GetMapping("/{orderNo}")
    public Map<String, Object> findByOrderNo(@PathVariable String orderNo) {
        StoredValueOrder order;
        try {
            order = storedValueService.requireByOrderNo(orderNo);
        } catch (Exception e) {
            // 订单确实不存在：返回 found=false，由调用方决定如何处理
            log.info("内部查询储值订单不存在 orderNo={}", orderNo);
            return Map.of("found", false);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("found", true);
        body.put("orderNo", order.getOrderNo());
        body.put("userId", order.getUserId());
        body.put("amount", order.getAmount());
        body.put("payStatus", order.getPayStatus());
        return body;
    }

    /**
     * 驱动储值订单入账（幂等）。
     *
     * <p>入账失败会抛出异常，由 trade 域转换为「回调应答 FAIL」，
     * 让微信按策略重试；幂等由 Service 层的条件更新保证，重试不会重复入账。
     */
    @PostMapping("/{orderNo}/settle")
    public Map<String, Object> settle(@PathVariable String orderNo,
                                      @RequestBody Map<String, Object> body) {
        String transactionId = asString(body.get("transactionId"));
        String payerOpenid = asString(body.get("payerOpenid"));
        Long callbackAmount = asLong(body.get("callbackAmount"));

        StoredValueOrder order = storedValueService.markPaid(
                orderNo, transactionId, payerOpenid, callbackAmount);
        log.info("内部驱动储值入账完成 orderNo={} payStatus={}", orderNo, order.getPayStatus());
        return Map.of(
                "orderNo", order.getOrderNo(),
                "payStatus", order.getPayStatus()
        );
    }

    /**
     * 储值余额支付扣款（供 trade 的「余额支付点单」调用）。
     *
     * <p><b>为什么放在 /internal 而不是 /api/v1/app</b>：扣款必须与
     * 「置订单已支付 + 写支付单」在同一个 trade 事务里完成，
     * 由服务端内部调用；小程序端只负责发起请求，不直接调扣款接口。
     *
     * <p>余额不足时返回 {@code success=false}，<b>不抛异常</b> ——
     * 「余额不足」是正常业务分支，需让 trade 得到明确结果而不是 500。
     *
     * @return { success: true } 或 { success: false, message: "..." }
     */
    @PostMapping("/balance/pay")
    public Map<String, Object> payWithBalance(@RequestBody Map<String, Object> body) {
        Long userId = asLong(body.get("userId"));
        Long amount = asLong(body.get("amount"));
        String bizNo = asString(body.get("bizNo"));
        if (userId == null || amount == null) {
            return Map.of("success", false, "message", "缺少 userId 或 amount");
        }
        try {
            storedValueService.payWithBalance(userId, amount, bizNo);
            return Map.of("success", true);
        } catch (BusinessException e) {
            // 业务性失败（余额不足等）如实回传，让 trade 决定是否回滚整个订单事务
            log.info("储值余额扣款未成功 userId={} amount={} bizNo={} reason={}",
                    userId, amount, bizNo, e.getMessage());
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    /**
     * 储值余额退回（供 trade 的「余额支付订单」退款/取消调用）。
     *
     * <p>口径：退款原路退回 —— 当初用余额付的，就退回余额（不是微信）。
     * 储值「充值」不可退，本接口与充值退款无关。
     *
     * @return { success: true } 或 { success: false, message: "..." }
     */
    @PostMapping("/balance/refund")
    public Map<String, Object> refundToBalance(@RequestBody Map<String, Object> body) {
        Long userId = asLong(body.get("userId"));
        Long amount = asLong(body.get("amount"));
        String bizNo = asString(body.get("bizNo"));
        if (userId == null || amount == null) {
            return Map.of("success", false, "message", "缺少 userId 或 amount");
        }
        try {
            storedValueService.refundToBalance(userId, amount, bizNo);
            return Map.of("success", true);
        } catch (BusinessException e) {
            log.info("储值余额退回未成功 userId={} amount={} bizNo={} reason={}",
                    userId, amount, bizNo, e.getMessage());
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    private String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private Long asLong(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
