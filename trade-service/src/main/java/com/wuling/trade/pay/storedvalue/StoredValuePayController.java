package com.wuling.trade.pay.storedvalue;

import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.security.CurrentUser;
import com.wuling.trade.pay.wxpay.WxPayPrepayResult;
import com.wuling.trade.port.UserQueryPort;
import com.wuling.trade.service.PaymentGatewayResolver;
import com.wuling.trade.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 储值充值支付发起（第 15 期）。
 *
 * <p><b>路径</b>：{@code POST /api/v1/app/payments/stored-value/prepay}
 *
 * <p><b>为什么放在 trade 域而不是 marketing 域</b>：
 * 微信支付网关（{@code PaymentGatewayResolver} / {@code WechatPayGateway}）
 * 与 {@code payment} 表都在 trade 域。若由 marketing 域发起下单，
 * 需要反向依赖 trade 的支付实现，形成双向依赖。
 * 因此由 trade 承接「支付编排」，通过 {@link StoredValueOrderPort}
 * 只读查询储值订单，不直接碰 marketing 的表。
 *
 * <p><b>安全</b>：
 * <ul>
 *   <li>订单归属校验：只允许支付本人订单，防替他人发起支付；</li>
 *   <li>金额取自服务端储值订单，<b>不接受前端传入</b>；</li>
 *   <li>openid 由服务端按 JWT 中的 userId 查询，<b>绝不接受前端传入</b>。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/app/payments/stored-value")
public class StoredValuePayController {

    private static final Logger log = LoggerFactory.getLogger(StoredValuePayController.class);

    private final StoredValueOrderPort storedValueOrderPort;
    private final PaymentService paymentService;
    private final PaymentGatewayResolver gatewayResolver;
    private final UserQueryPort userQueryPort;

    public StoredValuePayController(StoredValueOrderPort storedValueOrderPort,
                                    PaymentService paymentService,
                                    PaymentGatewayResolver gatewayResolver,
                                    UserQueryPort userQueryPort) {
        this.storedValueOrderPort = storedValueOrderPort;
        this.paymentService = paymentService;
        this.gatewayResolver = gatewayResolver;
        this.userQueryPort = userQueryPort;
    }

    /**
     * 发起储值充值支付。
     *
     * <p>请求体：{@code {"orderNo":"CZ..."}}
     */
    @PostMapping("/prepay")
    public Result<WxPayPrepayResult> prepay(@RequestBody Map<String, String> body) {
        String orderNo = body == null ? null : body.get("orderNo");
        if (orderNo == null || orderNo.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单号不能为空");
        }
        if (!PaymentService.isStoredValueOrder(orderNo)) {
            // 防止用本接口支付点单订单，绕过订单侧的校验
            throw new BusinessException(ResultCode.BAD_REQUEST, "非储值订单号");
        }

        Long userId = CurrentUser.require();
        StoredValueOrderPort.StoredValueOrderView order = storedValueOrderPort.findByOrderNo(orderNo);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "储值订单不存在");
        }
        if (!userId.equals(order.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权支付该储值订单");
        }
        if ("PAID".equalsIgnoreCase(order.getPayStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该储值订单已完成，请勿重复支付");
        }
        if (order.getAmount() == null || order.getAmount() <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "储值订单金额异常");
        }

        if (gatewayResolver.isMockChannel()) {
            // mock 通道：无真实收银台，交回前端按「本地模拟成功」处理。
            // 明确返回 null 而非伪造支付参数，避免前端误以为拿到了真实签名。
            log.info("储值支付走 mock 通道，不产生真实支付参数 orderNo={}", orderNo);
            return Result.ok(null);
        }

        String openid = userQueryPort.findOpenid(userId);
        if (openid == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "未获取到微信支付标识，请重新进入小程序后再试");
        }

        WxPayPrepayResult result = paymentService.prepayStoredValue(
                orderNo, order.getAmount(), openid, "会员储值");
        return Result.ok(result);
    }
}
