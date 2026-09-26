package com.wuling.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.common.alert.AlertChannel;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.common.mq.MqConstants;
import com.wuling.common.mq.MqProducer;
import com.wuling.common.mq.event.OrderPaidEvent;
import com.wuling.trade.dto.OrderDTO;
import com.wuling.trade.dto.PayRequest;
import com.wuling.trade.entity.Order;
import com.wuling.trade.entity.OrderItem;
import com.wuling.trade.entity.Payment;
import com.wuling.trade.mapper.OrderItemMapper;
import com.wuling.trade.mapper.PaymentMapper;
import com.wuling.trade.pay.giftcard.GiftCardOrderPort;
import com.wuling.trade.pay.wxpay.WxPayPrepayResult;
import com.wuling.trade.pay.wxpay.WxPayStatusMapper;
import com.wuling.trade.pay.storedvalue.StoredValueBalancePort;
import com.wuling.trade.pay.storedvalue.StoredValueOrderPort;
import com.wuling.trade.pay.wxpay.WxPayTransaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 支付服务。
 *
 * <p>幂等：
 * <ul>
 *   <li>同一订单已支付时，重复回调直接返回成功，不重复入账；</li>
 *   <li>通过订单行锁 + 状态机保证并发安全。</li>
 * </ul>
 *
 * <p><b>第 14 期改动（支付通道接入）</b>：
 * <ol>
 *   <li>不再直接依赖 {@code MockPaymentGateway}，改由
 *       {@link PaymentGatewayResolver} 按 {@code app.pay.channel} 选型；</li>
 *   <li>新增 {@link #prepayForMiniApp} 与 {@link #handleWxPayCallback}，
 *       供微信支付通道使用（默认 mock 通道下不会被调用）；</li>
 *   <li>{@link #handleWxPayCallback} <b>强制校验回调金额与订单实付金额一致</b> ——
 *       原 {@code handleCallback} 是不校验金额的，属资损敞口。</li>
 * </ol>
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final OrderService orderService;
    private final PaymentMapper paymentMapper;
    private final OrderItemMapper orderItemMapper;
    private final PaymentGatewayResolver gatewayResolver;
    private final AlertChannel alertChannel;
    private final StoredValueOrderPort storedValueOrderPort;

    /** 礼品卡订单资金端口：建支付单、支付回调、退款编排 */
    private final GiftCardOrderPort giftCardOrderPort;

    /**
     * 储值余额资金端口：点单订单的余额支付扣款与退款回冲。
     *
     * <p>与 {@link #storedValueOrderPort}（管充值单生命周期）职责不同，
     * 这里管「余额这笔钱本身」的进出。
     */
    private final StoredValueBalancePort storedValueBalancePort;

    /**
     * 余额支付意图：扣款前用 REQUIRES_NEW 独立提交，补偿任务据此扫悬挂单。
     */
    private final BalancePayIntentService balancePayIntentService;

    /**
     * 支付成功事件发布器（第 16 期：邀请奖励发放）。
     *
     * <p><b>为什么用事件而不是直接调营销接口</b>：发奖不是支付的必经环节，
     * 一旦同步调用失败就会把「用户已付款」的回调拖失败，微信会持续重推。
     * 改由事件驱动：trade 只负责声明「付成功了」，发奖交给 marketing 异步重试。
     */
    private final MqProducer mqProducer;

    public PaymentService(OrderService orderService,
                          PaymentMapper paymentMapper,
                          OrderItemMapper orderItemMapper,
                          PaymentGatewayResolver gatewayResolver,
                          AlertChannel alertChannel,
                          StoredValueOrderPort storedValueOrderPort,
                          StoredValueBalancePort storedValueBalancePort,
                          BalancePayIntentService balancePayIntentService,
                          MqProducer mqProducer,
                          GiftCardOrderPort giftCardOrderPort) {
        this.orderService = orderService;
        this.paymentMapper = paymentMapper;
        this.orderItemMapper = orderItemMapper;
        this.gatewayResolver = gatewayResolver;
        this.alertChannel = alertChannel;
        this.storedValueOrderPort = storedValueOrderPort;
        this.storedValueBalancePort = storedValueBalancePort;
        this.balancePayIntentService = balancePayIntentService;
        this.mqProducer = mqProducer;
        this.giftCardOrderPort = giftCardOrderPort;
    }

    /** 发起支付：创建支付单（不改变订单状态） */
    @Transactional(rollbackFor = Exception.class)
    public Payment prepay(String orderNo, PayRequest request) {
        Order order = orderService.lockForUpdate(orderNo);
        if (OrderService.STATUS_CREATED.equals(order.getStatus())) {
            // 待支付，正常发起
        } else if (OrderService.STATUS_PAID.equals(order.getStatus())
                || OrderService.STATUS_COMPLETED.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单已支付，请勿重复支付");
        } else {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单状态不可支付: " + order.getStatus());
        }
        if (!order.getPaidAmount().equals(request.getAmount())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "支付金额与订单金额不一致");
        }

        Payment payment = new Payment();
        payment.setPaymentNo(gatewayResolver.active().prepay(orderNo, request.getAmount()));
        payment.setOrderId(order.getId());
        payment.setOrderNo(orderNo);
        payment.setBizType(Payment.BIZ_ORDER);
        payment.setBizNo(orderNo);
        payment.setAmount(request.getAmount());
        payment.setChannel(request.getChannel());
        payment.setThirdStatus("PENDING");
        payment.setStandardStatus("PAYING");
        paymentMapper.insert(payment);
        return payment;
    }

    /**
     * 小程序支付下单（第 14 期新增）。
     *
     * <p>与 {@link #prepay} 的区别：
     * <ul>
     *   <li>调用第三方通道的 {@code prepayForMiniApp}，拿到小程序唤起收银台所需参数；</li>
     *   <li>把 {@code prepay_id} 落库到 {@code payment.prepay_id}，便于排查与对账；</li>
     *   <li><b>不改变订单状态</b> —— 订单仍为待支付，直到回调到达。</li>
     * </ul>
     *
     * <p><b>为什么必须传 openid</b>：微信 JSAPI 支付要求下单时指定支付者，
     * 且该 openid 必须在商户号绑定的小程序下。openid 取自登录态（服务端），
     * <b>绝不接受前端传入</b>，否则可替他人发起支付。
     *
     * @param orderNo     订单号
     * @param amountFen   金额（分），必须与订单实付一致
     * @param payerOpenid 当前登录用户的 openid
     * @param description 商品描述
     * @return 下单结果（含 prepay_id 与小程序支付参数）
     */
    @Transactional(rollbackFor = Exception.class)
    public WxPayPrepayResult prepayForMiniApp(String orderNo, long amountFen,
                                              String payerOpenid, String description) {
        Order order = orderService.lockForUpdate(orderNo);
        if (OrderService.STATUS_PAID.equals(order.getStatus())
                || OrderService.STATUS_COMPLETED.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单已支付，请勿重复支付");
        }
        if (!OrderService.STATUS_CREATED.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单状态不可支付: " + order.getStatus());
        }
        // 服务端二次校验金额，不信任前端传入
        if (!order.getPaidAmount().equals(amountFen)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "支付金额与订单金额不一致");
        }

        String channel = gatewayResolver.activeChannel();
        WxPayPrepayResult result = gatewayResolver.active()
                .prepayForMiniApp(orderNo, amountFen, payerOpenid, description);
        if (result == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "当前支付通道不支持小程序支付: " + channel);
        }

        Payment payment = new Payment();
        // 支付单号用「本次下单请求」的唯一标识，便于与微信侧对账
        payment.setPaymentNo(nextPaymentNo(orderNo));
        payment.setOrderId(order.getId());
        payment.setOrderNo(orderNo);
        payment.setBizType(Payment.BIZ_ORDER);
        payment.setBizNo(orderNo);
        payment.setAmount(amountFen);
        payment.setChannel(channel);
        payment.setThirdStatus("PENDING");
        payment.setStandardStatus("PAYING");
        payment.setPrepayId(result.getPrepayId());
        payment.setPayerOpenid(payerOpenid);
        paymentMapper.insert(payment);
        return result;
    }

    /**
     * 支付回调：置订单已支付 + 生成取餐码（mock / 内部网关通道）。
     *
     * <p>注意：分账在「核销」时执行（未消费不入账）。
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderDTO handleCallback(String orderNo, String transactionId) {
        return settlePaid(orderNo, transactionId, null, "MOCK");
    }

    /**
     * 微信支付回调入账（第 14 期新增）。
     *
     * <p>在入账前做<b>金额强校验</b>：
     * 微信回调里的 {@code amount.total} 必须与订单 {@code paidAmount} 完全一致，
     * 不一致则拒绝入账并触发告警。
     *
     * <p>为什么这条校验必须放在「入账前」而不是「记录日志」：
     * 金额不符意味着回调可能被伪造或与订单不匹配，
     * 一旦入账就等于按错误金额交付了商品，属资金损失。
     *
     * @param transaction 已验签解密的微信交易信息
     */
    /**
     * 储值订单号前缀（与 marketing 域 StoredValueService#nextNo 的 "CZ" 强绑定）。
     *
     * <p>为什么靠前缀识别业务域：微信回调只带回 out_trade_no，
     * 此时对应支付单可能尚未落库（下单与回调存在竞态）。
     * 按单号前缀判断无需查库即可路由。两处前缀若不一致，
     * 储值回调会被误判为订单回调，因查不到订单而失败。
     */
    public static final String STORED_VALUE_ORDER_PREFIX = "CZ";

    /** 判断单号是否属于储值业务 */
    public static boolean isStoredValueOrder(String orderNo) {
        return orderNo != null && orderNo.startsWith(STORED_VALUE_ORDER_PREFIX);
    }

    /** 礼品卡订单号前缀（与 marketing 域 GiftCardService 的 "GC" 强绑定）。 */
    public static final String GIFT_CARD_ORDER_PREFIX = "GC";

    /**
     * 判断单号是否属于礼品卡业务。
     *
     * <p>严格只认 GC 开头，避免把 {@code ORDER-GC-001} 等普通订单误路由到礼品卡链路。
     */
    public static boolean isGiftCardOrder(String orderNo) {
        return orderNo != null && orderNo.startsWith(GIFT_CARD_ORDER_PREFIX);
    }

    /**
     * 储值充值下单（第 15 期新增）。
     *
     * <p>与 {@link #prepayForMiniApp} 的差异：本方法<b>不校验订单表</b>
     * （储值订单在 marketing 域），而是直接用调用方已校验过的金额下单，
     * 并落一条 bizType=STORED_VALUE 的支付单，供回调路由与对账使用。
     *
     * <p>金额与 openid 均由调用方（{@code StoredValuePayController}）
     * 在服务端完成校验与查询，本方法不接受未校验的外部输入。
     *
     * @param orderNo      储值订单号（CZ 前缀）
     * @param amountFen    金额（分），已由服务端储值订单确认
     * @param payerOpenid  支付者 openid，服务端按 JWT 查询所得
     * @param description  收银台展示描述
     */
    @Transactional(rollbackFor = Exception.class)
    public WxPayPrepayResult prepayStoredValue(String orderNo, long amountFen,
                                               String payerOpenid, String description) {
        String channel = gatewayResolver.activeChannel();
        WxPayPrepayResult result = gatewayResolver.active()
                .prepayForMiniApp(orderNo, amountFen, payerOpenid, description);
        if (result == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "当前支付通道不支持小程序支付: " + channel);
        }

        Payment payment = new Payment();
        payment.setPaymentNo(nextPaymentNo(orderNo));
        // 储值「充值」本身不产生系统订单，故 orderId 为空；
        // orderNo 也不再留空 —— 运营后台「支付记录」按订单号检索，
        // 且需与「储值余额下单买商品」的订单退款对账，故写入储值单号（CZ…）。
        // biz_no 存同一值，保持「业务单号」语义完整；三方流水号在回调成功时回填。
        payment.setOrderId(null);
        payment.setOrderNo(orderNo);
        payment.setBizType(Payment.BIZ_STORED_VALUE);
        payment.setBizNo(orderNo);
        payment.setAmount(amountFen);
        payment.setChannel(channel);
        payment.setThirdStatus("PENDING");
        payment.setStandardStatus("PAYING");
        payment.setPrepayId(result.getPrepayId());
        payment.setPayerOpenid(payerOpenid);
        paymentMapper.insert(payment);
        return result;
    }

    /**
     * 礼品卡微信 JSAPI 下单。
     *
     * <p>订单归属、状态、金额和 openid 均由 {@code GiftCardPayController} 在服务端校验；
     * 本方法只负责向微信下单并落一条 GIFT_CARD 支付记录。订单仍是待支付，直到回调到账。
     */
    @Transactional(rollbackFor = Exception.class)
    public WxPayPrepayResult prepayGiftCard(String orderNo, long amountFen,
                                            String payerOpenid, String description,
                                            LocalDateTime expireTime) {
        String channel = gatewayResolver.activeChannel();
        WxPayPrepayResult result = gatewayResolver.active()
                .prepayForMiniApp(orderNo, amountFen, payerOpenid, description, expireTime);
        if (result == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "当前支付通道不支持小程序支付: " + channel);
        }

        Payment payment = findGiftCardPayment(orderNo);
        boolean existingPayment = payment != null;
        if (!existingPayment) {
            payment = new Payment();
            payment.setPaymentNo(giftCardPaymentNo(orderNo));
        }
        payment.setOrderId(null);
        payment.setOrderNo(orderNo);
        payment.setBizType(Payment.BIZ_GIFT_CARD);
        payment.setBizNo(orderNo);
        payment.setAmount(amountFen);
        payment.setChannel(channel);
        payment.setThirdStatus("PENDING");
        payment.setStandardStatus("PAYING");
        payment.setPrepayId(result.getPrepayId());
        payment.setPayerOpenid(payerOpenid);
        saveGiftCardPayment(payment, existingPayment);
        return result;
    }

    /**
     * 微信支付回调统一入口：按单号前缀路由到「订单」「储值」或「礼品卡」入账链路。
     *
     * <p>第 15 期新增。此前 {@link #handleWxPayCallback} 直接查 order 表，
     * 而储值单号（CZ 前缀）在 order 表中不存在，导致回调必然失败：
     * 应答 FAIL → 微信重试仍失败 → 用户已付款但余额永不入账。
     *
     * @return 订单支付返回订单视图；储值支付返回 null（储值无订单实体）
     */
    public OrderDTO routeWxPayCallback(WxPayTransaction transaction) {
        String orderNo = transaction.getOutTradeNo();
        if (isGiftCardOrder(orderNo)) {
            handleGiftCardCallback(transaction);
            return null;
        }
        if (isStoredValueOrder(orderNo)) {
            handleStoredValueCallback(transaction);
            return null;
        }
        return handleWxPayCallback(transaction);
    }

    /**
     * 礼品卡支付成功入账。
     *
     * <p>订单与卡在 marketing 域，trade 只在本地支付记录中回填微信交易号和付款人。
     * marketing 的 settle 对 UNPAID、已取消、已超时订单都做条件恢复，因此即使本地先超时，
     * 只要收到合法足额回调，资金事实仍能补发礼品卡。
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleGiftCardCallback(WxPayTransaction transaction) {
        String orderNo = transaction.getOutTradeNo();
        String transactionId = transaction.getTransactionId();
        Long callbackAmount = transaction.getTotalAmount();
        if (!StringUtils.hasText(transactionId)) {
            rejectGiftCardCallback(orderNo, transactionId, callbackAmount,
                    "回调缺少微信交易号");
        }
        if (callbackAmount == null) {
            rejectGiftCardCallback(orderNo, transactionId, callbackAmount,
                    "回调缺少支付金额");
        }

        GiftCardOrderPort.GiftCardOrderView order = giftCardOrderPort.findByOrderNo(orderNo);
        if (order == null) {
            log.error("礼品卡订单不存在，回调无法入账 orderNo={} 微信交易号={}",
                    orderNo, transactionId);
            throw new BusinessException(ResultCode.BAD_REQUEST, "礼品卡订单不存在: " + orderNo);
        }
        if (order.getAmount() == null || !order.getAmount().equals(callbackAmount)) {
            rejectGiftCardCallback(orderNo, transactionId, callbackAmount,
                    "回调金额与礼品卡订单金额不一致");
        }
        if (StringUtils.hasText(order.getTransactionId())
                && !order.getTransactionId().equals(transactionId)) {
            rejectGiftCardCallback(orderNo, transactionId, callbackAmount,
                    "回调微信交易号与礼品卡订单不一致");
        }

        boolean refunded = "REFUNDED".equalsIgnoreCase(order.getPayStatus());
        boolean refunding = "REFUNDING".equalsIgnoreCase(order.getRefundStatus());
        boolean alreadyPaid = "PAID".equalsIgnoreCase(order.getPayStatus());
        if (!refunded && !refunding && !alreadyPaid) {
            giftCardOrderPort.settle(orderNo, transactionId,
                    transaction.getPayerOpenid(), callbackAmount);
        }

        if (refunded) {
            syncGiftCardPaymentAfterSuccess(transaction, "REFUNDED", "REFUND");
        } else if (refunding) {
            syncGiftCardPaymentAfterSuccess(transaction, "REFUNDING", "REFUNDING");
        } else {
            syncGiftCardPaymentAfterSuccess(transaction, "PAID", "SUCCESS");
        }
    }

    private void rejectGiftCardCallback(String orderNo, String transactionId,
                                        Long callbackAmount, String message) {
        String detail = "订单号=" + orderNo
                + " 回调金额=" + callbackAmount
                + " 微信交易号=" + transactionId;
        log.error("{} orderNo={} {}", message, orderNo, detail);
        alertChannel.send(AlertChannel.Level.CRITICAL,
                "礼品卡支付回调校验失败", message + "；" + detail, orderNo);
        throw new BusinessException(ResultCode.BAD_REQUEST, message);
    }

    /**
     * 以微信支付成功事实同步 trade 侧支付记录。
     *
     * <p>重复回调也必须执行本方法，用于修复第一次回调在 marketing 入账后、
     * payment 更新前中断造成的不一致；退款中/已退款的状态不能被迟到的支付回调回退。
     */
    private void syncGiftCardPaymentAfterSuccess(WxPayTransaction transaction,
                                                 String desiredStandardStatus,
                                                 String desiredThirdStatus) {
        String orderNo = transaction.getOutTradeNo();
        Payment payment = findGiftCardPayment(orderNo);
        boolean newPayment = payment == null;
        if (newPayment) {
            payment = new Payment();
            payment.setPaymentNo(giftCardPaymentNo(orderNo));
        }

        payment.setOrderNo(orderNo);
        payment.setBizType(Payment.BIZ_GIFT_CARD);
        payment.setBizNo(orderNo);
        payment.setAmount(transaction.getTotalAmount());
        if (!StringUtils.hasText(payment.getChannel())) {
            payment.setChannel("WXPAY");
        }
        if (!shouldPreserveRefundStatus(payment.getStandardStatus(), desiredStandardStatus)) {
            payment.setStandardStatus(desiredStandardStatus);
            payment.setThirdStatus(desiredThirdStatus);
        }
        payment.setTransactionId(transaction.getTransactionId());
        payment.setPayerOpenid(transaction.getPayerOpenid());
        payment.setCallbackTime(LocalDateTime.now());

        saveGiftCardPayment(payment, !newPayment);
    }

    private boolean shouldPreserveRefundStatus(String existingStandardStatus,
                                               String desiredStandardStatus) {
        if ("REFUNDED".equalsIgnoreCase(existingStandardStatus)) {
            return true;
        }
        return "REFUNDING".equalsIgnoreCase(existingStandardStatus)
                && "PAID".equalsIgnoreCase(desiredStandardStatus);
    }

    private Payment findGiftCardPayment(String orderNo) {
        return paymentMapper.selectOne(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getBizType, Payment.BIZ_GIFT_CARD)
                .eq(Payment::getOrderNo, orderNo)
                .orderByDesc(Payment::getId)
                .last("limit 1"));
    }

    /**
     * 礼品卡一单一卡一支付，支付单号必须由订单号确定。
     *
     * <p>并发预下单时两个请求可能同时查不到支付记录；相同 paymentNo 会命中
     * {@code uk_payment_no}，从而保证只落一条支付记录，而不是产生多条分叉记录。
     */
    private String giftCardPaymentNo(String orderNo) {
        return "GCPAY-" + orderNo;
    }

    private void saveGiftCardPayment(Payment payment, boolean existingPayment) {
        if (existingPayment) {
            paymentMapper.updateById(payment);
            return;
        }
        try {
            paymentMapper.insert(payment);
        } catch (DuplicateKeyException duplicateKey) {
            // 并发请求可能都在首次查询时看不到记录。牺牲一次预支付结果，更新胜出的记录，
            // 后续回调仍只按 orderNo 定位这一条支付记录。
            Payment concurrent = paymentMapper.selectOne(new LambdaQueryWrapper<Payment>()
                    .eq(Payment::getPaymentNo, payment.getPaymentNo())
                    .eq(Payment::getBizType, Payment.BIZ_GIFT_CARD)
                    .last("limit 1 for update"));
            if (concurrent == null || concurrent.getId() == null) {
                throw duplicateKey;
            }
            payment.setId(concurrent.getId());
            paymentMapper.updateById(payment);
        }
    }
    /**
     * 储值订单入账（第 15 期新增）。
     *
     * <p>金额校验在 marketing 侧完成（它以订单金额为权威值），
     * 这里只把回调信息透传过去并驱动幂等入账。
     *
     * <p>入账失败刻意抛异常，由回调控制器应答 FAIL 让微信重试：
     * 宁可重复回调（marketing 侧条件更新兜底幂等），也不能丢单。
     */
    public void handleStoredValueCallback(WxPayTransaction transaction) {
        String orderNo = transaction.getOutTradeNo();
        StoredValueOrderPort.StoredValueOrderView order =
                storedValueOrderPort.findByOrderNo(orderNo);
        if (order == null) {
            // 查不到业务单仍抛异常：静默吞掉等于放弃一笔已收款的回调
            log.error("储值订单不存在，回调无法入账 orderNo={} 微信交易号={}",
                    orderNo, transaction.getTransactionId());
            throw new BusinessException(ResultCode.BAD_REQUEST, "储值订单不存在: " + orderNo);
        }
        if ("PAID".equalsIgnoreCase(order.getPayStatus())) {
            log.info("duplicate stored value callback ignored, orderNo={} notifyId={}",
                    orderNo, transaction.getNotifyId());
            return;
        }
        storedValueOrderPort.markPaid(orderNo, transaction.getTransactionId(),
                null, transaction.getTotalAmount());
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderDTO handleWxPayCallback(WxPayTransaction transaction) {
        String orderNo = transaction.getOutTradeNo();
        Long callbackAmount = transaction.getTotalAmount();

        Order order = orderService.lockForUpdate(orderNo);
        // 幂等：已支付订单直接返回，不重复入账（微信会重试回调）
        if (isDuplicatePaymentCallback(order)) {
            log.info("duplicate wxpay callback ignored, orderNo={} notifyId={}",
                    orderNo, transaction.getNotifyId());
            return orderService.toDTO(order, loadItems(order.getId()));
        }

        if (callbackAmount == null || !order.getPaidAmount().equals(callbackAmount)) {
            String detail = "订单金额=" + order.getPaidAmount()
                    + " 回调金额=" + callbackAmount
                    + " 微信交易号=" + transaction.getTransactionId();
            log.error("微信支付回调金额不一致，拒绝入账 orderNo={} {}", orderNo, detail);
            alertChannel.send(AlertChannel.Level.CRITICAL,
                    "微信支付回调金额不一致", detail, orderNo);
            throw new BusinessException(ResultCode.BAD_REQUEST, "回调金额与订单金额不一致");
        }

        return settlePaid(orderNo, transaction.getTransactionId(),
                WxPayStatusMapper.STANDARD_PAID, "WXPAY");
    }

    /**
     * 储值余额支付点单订单（同步扣款，一次事务内完成）。
     *
     * <p><b>与微信支付的核心区别</b>：余额支付没有第三方、没有回调，
     * 资金动作必须在本次请求内同步完成，因此这里一次性做完：
     * <ol>
     *   <li>行锁订单，校验状态与金额（与微信链路同一套前置校验）；</li>
     *   <li>调 marketing 扣减余额（扣除成负数由 DB 条件保证）；</li>
     *   <li>置订单已支付 + 生成取餐码；</li>
     *   <li>写支付单：channel=STORED_VALUE，bizType=ORDER，
     *       <b>流水订单号 = 订单号</b>（余额支付无三方单号，按业务口径取订单号）。</li>
     * </ol>
     *
     * <p><b>为什么整段必须在一个事务里</b>：扣了余额却没置订单已支付
     * （或反之）都会造成资金与业务状态不一致。注意营销域扣款是远程调用，
     * 事务只能回滚本地库；因此扣款失败时我们<b>主动抛异常</b>，
     * 且营销域的扣款本身也是独立事务并已提交 —— 所以流程上先扣款、
     * 成功后立即完成订单落库，把「已扣款但订单未更新」的窗口压到最小。
     *
     * @param orderNo    订单号
     * @param userId     用户 ID（服务端取自登录态，不接受前端传入）
     * @param amountFen  支付金额（分），必须与订单实付一致
     * @return 支付完成后的订单视图
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderDTO payWithStoredValue(String orderNo, Long userId, long amountFen) {
        Order order = orderService.lockForUpdate(orderNo);
        if (OrderService.STATUS_PAID.equals(order.getStatus())
                || OrderService.STATUS_COMPLETED.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单已支付，请勿重复支付");
        }
        if (!OrderService.STATUS_CREATED.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单状态不可支付: " + order.getStatus());
        }
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权支付该订单");
        }
        // 服务端二次校验金额，不信任前端传入
        if (!order.getPaidAmount().equals(amountFen)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "支付金额与订单金额不一致");
        }

        // 扣款前登记支付意图（REQUIRES_NEW 独立提交）：即便下面主事务回滚，
        // 意图记录仍在，补偿任务据此发现「已扣款但订单未支付」并回冲余额。
        balancePayIntentService.register(orderNo, userId, amountFen);

        StoredValueBalancePort.DeductResult deduct =
                storedValueBalancePort.deduct(userId, amountFen, orderNo);
        if (!deduct.success()) {
            // 余额不足等业务性失败：抛出让事务回滚，前端展示可读原因。
            // 意图仍为 PENDING，但既然没有扣到款，补偿任务判定为「无悬挂」即可
            //（见 BalancePayReconcileJob 的判定：只有订单未支付且意图 PENDING 才回冲，
            //   而这里压根没扣成，回冲也是 0 效果 —— 但为防误判，会把意图标记 COMPENSATED）。
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    StringUtils.hasText(deduct.message()) ? deduct.message() : "储值余额不足，请先充值");
        }

        LocalDateTime now = LocalDateTime.now();
        orderService.markPaid(order, now);

        Payment payment = new Payment();
        payment.setPaymentNo(nextPaymentNo(orderNo));
        payment.setOrderId(order.getId());
        payment.setOrderNo(orderNo);
        payment.setBizType(Payment.BIZ_ORDER);
        payment.setBizNo(orderNo);
        payment.setAmount(amountFen);
        payment.setChannel("STORED_VALUE");
        payment.setThirdStatus("SUCCESS");
        payment.setStandardStatus("PAID");
        // 余额支付没有三方订单号，按业务口径「流水订单号 = 订单号」，
        // 保证后台支付记录的流水订单号列不空白、且可与订单对账。
        payment.setTransactionId(orderNo);
        payment.setCallbackTime(now);
        paymentMapper.insert(payment);

        // 扣款 + 订单落库都成功：意图进入正常终态 DONE
        balancePayIntentService.markDone(orderNo);

        publishOrderPaid(order, "STORED_VALUE");

        log.info("stored value pay ok orderNo={} userId={} amount={}",
                orderNo, userId, amountFen);
        return orderService.toDTO(order, loadItems(order.getId()));
    }

    /**
     * 判断某笔支付是否为「储值余额支付」。
     *
     * <p>退款时据此决定资金去向：余额支付的退回储值余额，
     * 微信支付的走微信原路退款。
     */
    public static boolean isStoredValueChannel(String channel) {
        return channel != null && "STORED_VALUE".equalsIgnoreCase(channel.trim());
    }

    private boolean isDuplicatePaymentCallback(Order order) {
        if (OrderService.STATUS_PAID.equals(order.getStatus())
                || OrderService.STATUS_COMPLETED.equals(order.getStatus())) {
            return true;
        }
        return OrderService.STATUS_CANCELED.equals(order.getStatus())
                && "REFUNDED".equalsIgnoreCase(order.getRefundStatus());
    }


    /**
     * 入账核心逻辑：行锁 + 状态机 + 支付单落库。
     *
     * <p>mock 与微信两条回调路径共用，避免两套入账逻辑漂移。
     * {@code expectedStandardStatus} 为 null 时表示由调用方已完成校验（mock 路径）。
     */
    private OrderDTO settlePaid(String orderNo, String transactionId,
                                String expectedStandardStatus, String channelTag) {
        Order order = orderService.lockForUpdate(orderNo);

        if (isDuplicatePaymentCallback(order)) {
            log.info("duplicate callback ignored, orderNo={}", orderNo);
            return orderService.toDTO(order, loadItems(order.getId()));
        }
        if (!OrderService.STATUS_CREATED.equals(order.getStatus())) {
            // 典型场景：订单已被 MQ 超时关闭，但用户实际已付款 —— 需人工介入退款
            String detail = "订单状态=" + order.getStatus()
                    + " 渠道=" + channelTag
                    + " 第三方交易号=" + transactionId
                    + " 金额=" + order.getPaidAmount();
            log.error("回调到达但订单状态不可支付，需人工核查 orderNo={} {}", orderNo, detail);
            alertChannel.send(AlertChannel.Level.CRITICAL,
                    "支付回调到达但订单已关闭", detail, orderNo);
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单状态不可支付: " + order.getStatus());
        }

        LocalDateTime now = LocalDateTime.now();
        orderService.markPaid(order, now);

        Payment payment = paymentMapper.selectOne(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getOrderNo, orderNo).orderByDesc(Payment::getId).last("limit 1"));
        if (payment != null) {
            // third_status 保留第三方原始值，便于追溯；standard_status 用项目口径
            payment.setThirdStatus(expectedStandardStatus == null ? "SUCCESS" : expectedStandardStatus);
            payment.setStandardStatus("PAID");
            // 流水订单号（三方订单号）：回调未带回时回落到系统订单号，
            // 保证后台「流水订单号」列可用（储值支付场景该值即订单号）。
            payment.setTransactionId(
                    StringUtils.hasText(transactionId) ? transactionId : orderNo);
            payment.setCallbackTime(now);
            paymentMapper.updateById(payment);
        }
        publishOrderPaid(order, channelTag);
        log.info("order paid, orderNo={} channel={} pickupCode={}", orderNo, channelTag, order.getPickupCode());
        return orderService.toDTO(order, loadItems(order.getId()));
    }

    /** Mock 直接支付：创建支付单并立即置为已支付（用于联调与演示） */
    @Transactional(rollbackFor = Exception.class)
    public OrderDTO pay(String orderNo, PayRequest request) {
        prepay(orderNo, request);
        return handleCallback(orderNo, nextTxnId());
    }

    public List<Payment> paymentsOfOrder(String orderNo) {
        return paymentMapper.selectList(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getOrderNo, orderNo).orderByDesc(Payment::getId));
    }

    private List<OrderItem> loadItems(Long orderId) {
        return orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderId).orderByAsc(OrderItem::getId));
    }

    String nextTxnId() {
        return "TXN" + System.currentTimeMillis() % 100000000L
                + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
    }

    /**
     * 生成支付单号。
     *
     * <p>格式 {\code PAY + 时间戳 + 3 位随机}，与 Mock 通道的
     * {\code prepay} 返回值风格保持一致，便于日志里统一检索。
     * 同订单多次发起支付时会生成不同单号，历史支付单保留以便对账。
     */
    private String nextPaymentNo(String orderNo) {
        return "PAY" + System.currentTimeMillis() % 100000000L
                + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
    }


    /**
     * 发布「订单支付成功」事件（第 16 期：邀请奖励发放）。
     *
     * <p><b>为什么注册 afterCommit 而不是立即发送</b>：本方法调用点都在
     * 支付事务内（payWithStoredValue / settlePaid）。若在事务内发送，一旦后续
     * 语句回滚，营销侧已收到「支付成功」并可能已发奖，出现
     * 「订单没付成功但奖励已发」的资损敞口。
     *
     * <p><b>异常语义</b>：发奖链路任何异常都只记日志，绝不向上抛 ——
     * 抛异常会让「用户已付款」的回调失败并被微信持续重推。
     */
    private void publishOrderPaid(Order order, String channel) {
        if (order == null || order.getOrderNo() == null || order.getUserId() == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            doPublishOrderPaid(order, channel);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                doPublishOrderPaid(order, channel);
            }
        });
    }

    private void doPublishOrderPaid(Order order, String channel) {
        try {
            OrderPaidEvent event = new OrderPaidEvent();
            event.setOrderId(order.getId());
            event.setOrderNo(order.getOrderNo());
            event.setUserId(order.getUserId());
            event.setPaidAmount(order.getPaidAmount());
            event.setChannel(channel);
            mqProducer.send(MqConstants.PAYMENT_SUCCESS_ROUTING_KEY, event, order.getOrderNo());
        } catch (Exception e) {
            log.error("支付成功事件发布失败（不影响支付结果） orderNo={} err={}",
                    order.getOrderNo(), e.getMessage());
        }
    }}
