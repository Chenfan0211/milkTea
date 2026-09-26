package com.wuling.trade.service;

import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.trade.entity.Payment;
import com.wuling.trade.mapper.PaymentMapper;
import com.wuling.trade.pay.wxpay.WxPayStatusMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 支付「异常重试」：主动向三方查询订单真实状态并回写。
 *
 * <p><b>为什么不能直接改状态</b>：后台若把「重试」实现成把 {@code standard_status}
 * 直接置为 PAID，等于人工伪造一笔交易 —— 用户实际未付款却交付商品，属资金损失。
 * 因此重试必须是「查三方 → 按真实结果回写」。
 *
 * <p><b>可重试的前置条件</b>：仅当支付单处于「需要人工干预」的终态时才允许重试，
 * 即 {@code standard_status = FAILED}。「支付中」「已关闭」的单不开放手动重试
 * （用户可能正在收银台操作），已支付/已退款的单更无需重试。
 *
 * <p><b>回写口径</b>：三方状态经 {@link WxPayStatusMapper} 映射为系统标准状态，
 * 与回调链路共用同一套映射，避免出现两套口径。
 */
@Service
public class PaymentRetryService {

    private static final Logger log = LoggerFactory.getLogger(PaymentRetryService.class);

    /** 允许重试的系统标准状态：仅支付失败 */
    private static final Set<String> RETRYABLE =
            Set.of(WxPayStatusMapper.STANDARD_FAILED);

    private final PaymentMapper paymentMapper;
    private final PaymentGatewayResolver gatewayResolver;

    public PaymentRetryService(PaymentMapper paymentMapper, PaymentGatewayResolver gatewayResolver) {
        this.paymentMapper = paymentMapper;
        this.gatewayResolver = gatewayResolver;
    }

    /**
     * 重新查询三方状态并回写支付单。
     *
     * @param paymentId 支付单主键
     * @return 回写后的支付单（含最新 thirdStatus / standardStatus）
     */
    @Transactional(rollbackFor = Exception.class)
    public Payment retry(Long paymentId) {
        Payment payment = paymentMapper.selectById(paymentId);
        if (payment == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "支付单不存在");
        }
        String current = payment.getStandardStatus() == null
                ? "" : payment.getStandardStatus().toUpperCase();
        if (!RETRYABLE.contains(current)) {
            // 明确拒绝而不是静默放过：让操作者知道这单无需/不该重试
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "仅支付失败的支付单可重试，当前状态：" + current);
        }

        String orderNo = StringUtils.hasText(payment.getOrderNo())
                ? payment.getOrderNo() : payment.getBizNo();
        if (!StringUtils.hasText(orderNo)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "支付单缺少订单号，无法查询三方状态");
        }

        PaymentGateway.PaymentQueryResult result = gatewayResolver.active().queryOrder(orderNo);
        if (result == null) {
            // 查不到（订单不存在 / 通道不支持 / 网络异常）：保持原状态，如实告知
            String channel = gatewayResolver.activeChannel();
            log.warn("支付重试未取得三方状态 paymentId={} orderNo={} channel={}",
                    paymentId, orderNo, channel);
            throw new BusinessException(ResultCode.ERROR,
                    "未查询到三方订单状态（通道 " + channel + "），请稍后重试或核对商户后台");
        }

        String standard = WxPayStatusMapper.toStandardStatus(result.tradeState());
        if (standard == null) {
            // 未知状态不猜：保持原状态并提示，避免把未知当成成功
            throw new BusinessException(ResultCode.ERROR,
                    "三方返回未知交易状态：" + result.tradeState() + "，已保持原状态");
        }

        payment.setThirdStatus(StringUtils.hasText(result.tradeState())
                ? result.tradeState() : payment.getThirdStatus());
        payment.setStandardStatus(standard);
        if (StringUtils.hasText(result.transactionId())) {
            payment.setTransactionId(result.transactionId());
        }
        // 仅在确认支付成功时记录回调时间，避免把「查询时间」误标为「支付时间」
        if (WxPayStatusMapper.STANDARD_PAID.equals(standard)) {
            payment.setCallbackTime(LocalDateTime.now());
        }
        paymentMapper.updateById(payment);
        log.info("支付重试完成 paymentId={} orderNo={} thirdStatus={} standardStatus={}",
                paymentId, orderNo, payment.getThirdStatus(), standard);
        return payment;
    }
}