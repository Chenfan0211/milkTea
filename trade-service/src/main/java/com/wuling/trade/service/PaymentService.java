package com.wuling.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.common.alert.AlertChannel;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.trade.dto.OrderDTO;
import com.wuling.trade.dto.PayRequest;
import com.wuling.trade.entity.Order;
import com.wuling.trade.entity.OrderItem;
import com.wuling.trade.entity.Payment;
import com.wuling.trade.mapper.OrderItemMapper;
import com.wuling.trade.mapper.PaymentMapper;
import com.wuling.trade.pay.wxpay.WxPayPrepayResult;
import com.wuling.trade.pay.wxpay.WxPayStatusMapper;
import com.wuling.trade.pay.storedvalue.StoredValueOrderPort;
import com.wuling.trade.pay.wxpay.WxPayTransaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public PaymentService(OrderService orderService,
                          PaymentMapper paymentMapper,
                          OrderItemMapper orderItemMapper,
                          PaymentGatewayResolver gatewayResolver,
                          AlertChannel alertChannel,
                          StoredValueOrderPort storedValueOrderPort) {
        this.orderService = orderService;
        this.paymentMapper = paymentMapper;
        this.orderItemMapper = orderItemMapper;
        this.gatewayResolver = gatewayResolver;
        this.alertChannel = alertChannel;
        this.storedValueOrderPort = storedValueOrderPort;
    }

    /** 发起支付：创建支付单（不改变订单状态） */
    @Transactional(rollbackFor = Exception.class)
    public Payment prepay(String orderNo, PayRequest request) {
        Order order = orderService.lockForUpdate(orderNo);
        if (OrderService.STATUS_CREATED.equals(order.getStatus())) {
            // 待支付，正常发起
        } else if (OrderService.STATUS_PAID.equals(order.getStatus())
                || OrderService.STATUS_VERIFIED.equals(order.getStatus())) {
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
                || OrderService.STATUS_VERIFIED.equals(order.getStatus())) {
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
        // 储值支付单不关联 order 表，故 orderId / orderNo 留空
        payment.setOrderId(null);
        payment.setOrderNo(null);
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
     * 微信支付回调统一入口：按单号前缀路由到「订单」或「储值」入账链路。
     *
     * <p>第 15 期新增。此前 {@link #handleWxPayCallback} 直接查 order 表，
     * 而储值单号（CZ 前缀）在 order 表中不存在，导致回调必然失败：
     * 应答 FAIL → 微信重试仍失败 → 用户已付款但余额永不入账。
     *
     * @return 订单支付返回订单视图；储值支付返回 null（储值无订单实体）
     */
    public OrderDTO routeWxPayCallback(WxPayTransaction transaction) {
        String orderNo = transaction.getOutTradeNo();
        if (isStoredValueOrder(orderNo)) {
            handleStoredValueCallback(transaction);
            return null;
        }
        return handleWxPayCallback(transaction);
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
        if (OrderService.STATUS_PAID.equals(order.getStatus())
                || OrderService.STATUS_VERIFIED.equals(order.getStatus())) {
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
     * 入账核心逻辑：行锁 + 状态机 + 支付单落库。
     *
     * <p>mock 与微信两条回调路径共用，避免两套入账逻辑漂移。
     * {@code expectedStandardStatus} 为 null 时表示由调用方已完成校验（mock 路径）。
     */
    private OrderDTO settlePaid(String orderNo, String transactionId,
                                String expectedStandardStatus, String channelTag) {
        Order order = orderService.lockForUpdate(orderNo);

        if (OrderService.STATUS_PAID.equals(order.getStatus())
                || OrderService.STATUS_VERIFIED.equals(order.getStatus())) {
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
            payment.setTransactionId(transactionId);
            payment.setCallbackTime(now);
            paymentMapper.updateById(payment);
        }
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
}
