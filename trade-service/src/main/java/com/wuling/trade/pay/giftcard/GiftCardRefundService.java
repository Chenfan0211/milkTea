package com.wuling.trade.pay.giftcard;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.common.alert.AlertChannel;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.trade.entity.Payment;
import com.wuling.trade.mapper.PaymentMapper;
import com.wuling.trade.service.PaymentGateway;
import com.wuling.trade.service.PaymentGatewayResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 礼品卡退款编排服务。 */
@Service
public class GiftCardRefundService {

    private static final Logger log = LoggerFactory.getLogger(GiftCardRefundService.class);

    private final GiftCardOrderPort orderPort;
    private final PaymentGatewayResolver gatewayResolver;
    private final PaymentMapper paymentMapper;
    private final AlertChannel alertChannel;

    public GiftCardRefundService(GiftCardOrderPort orderPort,
                                 PaymentGatewayResolver gatewayResolver,
                                 PaymentMapper paymentMapper,
                                 AlertChannel alertChannel) {
        this.orderPort = orderPort;
        this.gatewayResolver = gatewayResolver;
        this.paymentMapper = paymentMapper;
        this.alertChannel = alertChannel;
    }

    /** 发起礼品卡退款。 */
    public GiftCardRefundResult refund(String orderNo, Long userId, String reason) {
        if (!StringUtils.hasText(orderNo)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单号不能为空");
        }
        GiftCardOrderPort.GiftCardOrderView order = orderPort.findByOrderNo(orderNo);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "礼品卡订单不存在");
        }
        if (userId == null || !userId.equals(order.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该礼品卡订单");
        }
        if (!"PAID".equalsIgnoreCase(order.getPayStatus())
                || !"PAID".equalsIgnoreCase(order.getStatus())
                || !"UNVERIFIED".equalsIgnoreCase(order.getVerifyStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "当前订单状态不可退款");
        }
        if (!StringUtils.hasText(order.getTransactionId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单缺少真实支付流水，请转人工处理");
        }
        if (order.getTransactionId().startsWith("DEMO-")) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "历史模拟支付订单请转人工退款");
        }
        if (order.getAmount() == null || order.getAmount() <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单退款金额异常");
        }
        // 只有真实支付链路才会落 GIFT_CARD 支付记录；缺记录说明历史数据或链路异常，
        // 此时不允许继续发起退款，避免订单进入退款中但后台无支付证据。
        requireGiftCardPayment(order);

        GiftCardRefundResult refund = orderPort.refundBegin(orderNo, reason);
        if (refund == null || !StringUtils.hasText(refund.refundNo())) {
            throw new BusinessException(ResultCode.ERROR, "礼品卡退款受理失败，请稍后重试");
        }
        updatePaymentStatus(orderNo, "REFUNDING", "REFUNDING");

        if (gatewayResolver.isMockChannel()) {
            // mock 环境没有微信退款回调；立即走同一套成功状态机，便于开发验收。
            orderPort.refundConfirm(orderNo, refund.refundNo(), null);
            updatePaymentStatus(orderNo, "REFUNDED", "REFUND");
            return new GiftCardRefundResult(
                    refund.refundNo(), refund.amount(), "SUCCESS");
        }

        PaymentGateway.RefundApplyResult gatewayResult;
        try {
            gatewayResult = gatewayResolver.active().refund(
                    orderNo, refund.refundNo(), refund.amount(), reason);
        } catch (RuntimeException e) {
            // 抛异常、网络超时等情况无法判断微信是否已经受理退款。此时必须保留 REFUNDING，
            // 否则订单恢复 PAID 后可能被核销，而微信侧实际已经退款，形成资损。
            String detail = "微信退款受理结果未知，订单保持退款中并等待人工查询。refundNo="
                    + refund.refundNo() + "，error=" + e.getClass().getSimpleName()
                    + ": " + e.getMessage();
            log.error("礼品卡退款受理结果未知 orderNo={} refundNo={} err={}",
                    orderNo, refund.refundNo(), e.getMessage(), e);
            alertChannel.send(AlertChannel.Level.CRITICAL,
                    "礼品卡退款受理结果未知", detail, orderNo);
            throw new BusinessException(ResultCode.ERROR,
                    "退款受理结果未知，请稍后查询订单状态再重试");
        }
        if (gatewayResult == null || !gatewayResult.accepted()) {
            String failReason = gatewayResult == null || !StringUtils.hasText(gatewayResult.failReason())
                    ? "支付通道未受理退款"
                    : gatewayResult.failReason();
            orderPort.refundFail(orderNo, refund.refundNo(), failReason);
            updatePaymentStatus(orderNo, "PAID", "SUCCESS");
            throw new BusinessException(ResultCode.ERROR, "微信退款发起失败：" + failReason);
        }
        return refund;
    }

    /** 同步微信退款通知终态到 marketing 订单状态机。 */
    public void onRefundResult(String orderNo, String refundNo, boolean success,
                               String wxRefundId, String failReason) {
        if (!StringUtils.hasText(orderNo) || !StringUtils.hasText(refundNo)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "退款回调缺少订单号或退款单号");
        }
        if (success) {
            orderPort.refundConfirm(orderNo, refundNo, wxRefundId);
            updatePaymentStatus(orderNo, "REFUNDED", "REFUND");
        } else {
            orderPort.refundFail(orderNo, refundNo,
                    StringUtils.hasText(failReason) ? failReason : "微信退款失败");
            updatePaymentStatus(orderNo, "PAID", "SUCCESS");
        }
    }

    private Payment requireGiftCardPayment(GiftCardOrderPort.GiftCardOrderView order) {
        Payment payment = findGiftCardPayment(order.getOrderNo());
        if (payment == null || payment.getId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "缺少礼品卡支付记录，请转人工处理");
        }
        if (!"PAID".equalsIgnoreCase(payment.getStandardStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "礼品卡支付记录不是已支付状态，请转人工处理");
        }
        if (payment.getAmount() == null || !payment.getAmount().equals(order.getAmount())) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "礼品卡支付记录金额与订单不一致，请转人工处理");
        }
        if (!StringUtils.hasText(payment.getTransactionId())
                || !payment.getTransactionId().equals(order.getTransactionId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "礼品卡支付流水与订单不一致，请转人工处理");
        }
        return payment;
    }

    /**
     * 同步 payment 表的支付/退款状态。
     *
     * <p>成功和失败回调都可能重复到达；这里用状态条件更新，防止迟到的失败回调把
     * 已退款记录重新改回已支付。payment 记录缺失时只记日志，不阻断微信退款终态落库。
     */
    private void updatePaymentStatus(String orderNo, String standardStatus, String thirdStatus) {
        Payment update = new Payment();
        update.setStandardStatus(standardStatus);
        update.setThirdStatus(thirdStatus);

        LambdaQueryWrapper<Payment> wrapper = new LambdaQueryWrapper<Payment>()
                .eq(Payment::getBizType, Payment.BIZ_GIFT_CARD)
                .eq(Payment::getOrderNo, orderNo);
        if ("REFUNDING".equalsIgnoreCase(standardStatus)) {
            // 退款只能从「已支付」进入处理中。
            wrapper.eq(Payment::getStandardStatus, "PAID");
        } else if ("REFUNDED".equalsIgnoreCase(standardStatus)) {
            // 退款成功可以修正未落地的退款中状态，但不能被重复回调改坏。
            wrapper.ne(Payment::getStandardStatus, "REFUNDED");
        } else if ("PAID".equalsIgnoreCase(standardStatus)) {
            // 只有退款中的记录才允许被失败回调恢复为已支付；已退款记录不回退。
            wrapper.eq(Payment::getStandardStatus, "REFUNDING");
        }
        int affected = paymentMapper.update(update, wrapper);
        if (affected == 0) {
            log.info("礼品卡支付状态同步为幂等跳过 orderNo={} status={}", orderNo, standardStatus);
        }
    }

    private Payment findGiftCardPayment(String orderNo) {
        return paymentMapper.selectOne(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getBizType, Payment.BIZ_GIFT_CARD)
                .eq(Payment::getOrderNo, orderNo)
                .orderByDesc(Payment::getId)
                .last("limit 1"));
    }
}
