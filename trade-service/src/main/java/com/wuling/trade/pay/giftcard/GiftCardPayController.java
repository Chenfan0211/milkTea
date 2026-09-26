package com.wuling.trade.pay.giftcard;

import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.security.CurrentUser;
import com.wuling.trade.pay.wxpay.WxPayPrepayResult;
import com.wuling.trade.port.UserQueryPort;
import com.wuling.trade.service.PaymentGatewayResolver;
import com.wuling.trade.service.PaymentService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 礼品卡微信支付与退款入口。
 *
 * <p>请求中的订单号只用于定位业务单，金额和 openid 均从服务端查询，
 * 不接受客户端传入，避免金额篡改和替他人发起支付。
 */
@RestController
@RequestMapping("/api/v1/app/payments/gift-card")
public class GiftCardPayController {

    private static final long DEFAULT_EXPIRE_MINUTES = 15L;

    private final GiftCardOrderPort giftCardOrderPort;
    private final PaymentService paymentService;
    private final PaymentGatewayResolver gatewayResolver;
    private final UserQueryPort userQueryPort;
    private final GiftCardRefundService giftCardRefundService;

    public GiftCardPayController(GiftCardOrderPort giftCardOrderPort,
                                 PaymentService paymentService,
                                 PaymentGatewayResolver gatewayResolver,
                                 UserQueryPort userQueryPort,
                                 GiftCardRefundService giftCardRefundService) {
        this.giftCardOrderPort = giftCardOrderPort;
        this.paymentService = paymentService;
        this.gatewayResolver = gatewayResolver;
        this.userQueryPort = userQueryPort;
        this.giftCardRefundService = giftCardRefundService;
    }

    /** 发起一张礼品卡的微信 JSAPI 支付。 */
    @PostMapping("/prepay")
    public Result<WxPayPrepayResult> prepay(@RequestBody Map<String, String> body) {
        String orderNo = body == null ? null : body.get("orderNo");
        if (!StringUtils.hasText(orderNo)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单号不能为空");
        }

        Long userId = CurrentUser.require();
        GiftCardOrderPort.GiftCardOrderView order = giftCardOrderPort.findByOrderNo(orderNo);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "礼品卡订单不存在");
        }
        if (!userId.equals(order.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权支付该礼品卡订单");
        }
        if (order.getAmount() == null || order.getAmount() <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "礼品卡订单金额异常");
        }
        if (!"UNPAID".equalsIgnoreCase(order.getPayStatus())
                || !"CREATED".equalsIgnoreCase(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该礼品卡订单状态不可支付");
        }

        LocalDateTime expireTime = order.getExpireTime() == null
                ? LocalDateTime.now().plusMinutes(DEFAULT_EXPIRE_MINUTES)
                : order.getExpireTime();
        if (!expireTime.isAfter(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "礼品卡订单已过期，请重新下单");
        }

        if (gatewayResolver.isMockChannel()) {
            giftCardOrderPort.settle(orderNo, "DEMO-" + orderNo, null, order.getAmount());
            return Result.ok(null);
        }

        String openid = userQueryPort.findOpenid(userId);
        if (!StringUtils.hasText(openid)) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "未获取到微信支付标识，请重新进入小程序后再试");
        }
        WxPayPrepayResult result = paymentService.prepayGiftCard(
                orderNo, order.getAmount(), openid, "礼品卡购买", expireTime);
        return Result.ok(result);
    }

    /** 发起原路全额退款；已核销、mock/历史订单不允许在此链路退款。 */
    @PostMapping("/refund")
    public Result<GiftCardRefundResult> refund(@RequestBody Map<String, String> body) {
        String orderNo = body == null ? null : body.get("orderNo");
        if (!StringUtils.hasText(orderNo)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单号不能为空");
        }
        String reason = body.get("reason");
        GiftCardRefundResult result =
                giftCardRefundService.refund(orderNo, CurrentUser.require(), reason);
        return Result.ok(result);
    }
}
