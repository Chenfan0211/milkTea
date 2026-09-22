package com.wuling.trade.controller;

import com.wuling.security.CurrentUser;
import com.wuling.common.api.Result;
import com.wuling.trade.dto.CreateOrderRequest;
import com.wuling.trade.dto.OrderDTO;
import com.wuling.trade.dto.PayRequest;
import com.wuling.trade.pay.wxpay.WxPayPrepayResult;
import com.wuling.trade.port.UserQueryPort;
import com.wuling.trade.service.OrderService;
import com.wuling.trade.service.PaymentGatewayResolver;
import com.wuling.trade.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 小程序端：下单 / 支付 / 订单查询 */
@RestController
@RequestMapping("/api/v1/app")
public class AppOrderController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AppOrderController.class);

    private final OrderService orderService;
    private final PaymentService paymentService;
    private final PaymentGatewayResolver gatewayResolver;
    private final UserQueryPort userQueryPort;
    private final com.wuling.common.security.PaymentCallbackSigner signer;
    private final com.wuling.common.audit.AuditLogService auditLog;

    public AppOrderController(OrderService orderService,
                              PaymentService paymentService,
                              PaymentGatewayResolver gatewayResolver,
                              UserQueryPort userQueryPort,
                              com.wuling.common.security.PaymentCallbackSigner signer,
                              com.wuling.common.audit.AuditLogService auditLog) {
        this.orderService = orderService;
        this.paymentService = paymentService;
        this.gatewayResolver = gatewayResolver;
        this.userQueryPort = userQueryPort;
        this.signer = signer;
        this.auditLog = auditLog;
    }

    @PostMapping("/orders")
    public Result<OrderDTO> create(@Valid @RequestBody CreateOrderRequest request) {
        // 下单用户一律取自 JWT，忽略请求体中的 userId，防止替他人下单
        request.setUserId(CurrentUser.require());
        return Result.ok(orderService.createOrder(request));
    }

    /** 我的订单（用户取自 JWT） */
    @GetMapping("/orders")
    public Result<List<OrderDTO>> list() {
        Long userId = CurrentUser.require();
        return Result.ok(orderService.pageOrders(1, 50, null, null).getRecords().stream()
                .filter(o -> o.getUserId().equals(userId))
                .toList());
    }

    /** 订单详情：校验归属，禁止查看他人订单 */
    @GetMapping("/orders/{orderNo}")
    public Result<OrderDTO> detail(@PathVariable String orderNo) {
        OrderDTO order = orderService.getByOrderNo(orderNo);
        if (!CurrentUser.require().equals(order.getUserId())) {
            throw new com.wuling.common.exception.BusinessException(
                    com.wuling.common.api.ResultCode.FORBIDDEN, "无权查看该订单");
        }
        return Result.ok(order);
    }

    /**
     * 发起支付。
     *
     * <p>按当前生效的支付通道分流（{@code app.pay.channel}）：
     * <ul>
     *   <li><b>mock</b>（默认）：下单并立即置为已支付，返回订单 DTO。
     *       保持与接入微信支付前完全一致的响应结构，
     *       因此本地开发、CI 与现有回归不受影响；</li>
     *   <li><b>wxpay</b>：调用微信统一下单，返回小程序唤起收银台的参数。
     *       <b>订单状态不变</b>（仍为待支付），
     *       最终以微信回调为准 —— 前端 {@code requestPayment} 的 success
     *       不代表资金到账，不能据此认为已支付。</li>
     * </ul>
     *
     * <p>openid 由服务端依据 JWT 中的 userId 查询，<b>不接受前端传入</b>。
     */
    @PostMapping("/orders/{orderNo}/pay")
    public Result<?> pay(@PathVariable String orderNo, @Valid @RequestBody PayRequest request) {
        if (gatewayResolver.isMockChannel()) {
            return Result.ok(paymentService.pay(orderNo, request));
        }

        // 真实支付通道：校验订单归属，防止替他人发起支付
        OrderDTO order = orderService.getByOrderNo(orderNo);
        Long userId = CurrentUser.require();
        if (!userId.equals(order.getUserId())) {
            throw new com.wuling.common.exception.BusinessException(
                    com.wuling.common.api.ResultCode.FORBIDDEN, "无权支付该订单");
        }

        String openid = userQueryPort.findOpenid(userId);
        if (openid == null) {
            throw new com.wuling.common.exception.BusinessException(
                    com.wuling.common.api.ResultCode.BAD_REQUEST,
                    "未获取到微信支付标识，请重新进入小程序后再试");
        }

        WxPayPrepayResult prepay = paymentService.prepayForMiniApp(
                orderNo, order.getPaidAmount(), openid, order.getSummary());
        auditLog.record(String.valueOf(userId), "PAY", "PREPAY", orderNo,
                "通道=" + gatewayResolver.activeChannel() + " 金额=" + order.getPaidAmount(), null);
        return Result.ok(prepay);
    }

    /**
     * 支付回调（第三方通道 / 网关调用）。
     *
     * 安全（P0 修复）：必须携带有效 HMAC 签名与时间戳，否则拒绝。
     * 原实现无任何鉴权，任何人构造 orderNo + transactionId 即可将订单刷为已支付并获得取餐码。
     *
     * @param orderNo       订单号
     * @param transactionId 第三方交易号
     * @param timestamp     回调时间戳（毫秒）
     * @param sign          HMAC-SHA256 签名（十六进制）
     */
    @PostMapping("/payments/callback")
    public Result<OrderDTO> callback(@RequestParam String orderNo,
                                     @RequestParam String transactionId,
                                     @RequestParam(required = false) Long timestamp,
                                     @RequestParam(required = false) String sign) {
        // 参数缺失 / 格式错误一律按验签失败处理，不泄露内部细节
        if (timestamp == null || sign == null || sign.isBlank()
                || !signer.verify(orderNo, transactionId, timestamp, sign, System.currentTimeMillis())) {
            log.warn("payment callback rejected: signature invalid, orderNo={}", orderNo);
            auditLog.record("SYSTEM", "PAY", "CALLBACK_REJECT", orderNo, "回调签名校验失败", null);
            throw new com.wuling.common.exception.BusinessException(
                    com.wuling.common.api.ResultCode.FORBIDDEN, "回调签名校验失败");
        }
        OrderDTO dto = paymentService.handleCallback(orderNo, transactionId);
        auditLog.record("SYSTEM", "PAY", "CALLBACK_OK", orderNo,
                "交易号=" + transactionId + " 金额=" + dto.getPaidAmount(), null);
        return Result.ok(dto);
    }
}

