package com.wuling.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.trade.dto.OrderDTO;
import com.wuling.trade.dto.PayRequest;
import com.wuling.trade.entity.Order;
import com.wuling.trade.entity.OrderItem;
import com.wuling.trade.entity.Payment;
import com.wuling.trade.mapper.OrderItemMapper;
import com.wuling.trade.mapper.PaymentMapper;
import com.wuling.finance.service.LedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 支付服务（Mock 适配）。
 * 幂等：
 * - 同一订单已支付时，重复回调直接返回成功，不重复入账；
 * - 通过订单行锁 + 状态机保证并发安全。
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final OrderService orderService;
    private final PaymentMapper paymentMapper;
    private final OrderItemMapper orderItemMapper;
    private final LedgerService ledgerService;
    private final MockPaymentGateway mockPaymentGateway;

    public PaymentService(OrderService orderService,
                          PaymentMapper paymentMapper,
                          OrderItemMapper orderItemMapper,
                          LedgerService ledgerService,
                          MockPaymentGateway mockPaymentGateway) {
        this.orderService = orderService;
        this.paymentMapper = paymentMapper;
        this.orderItemMapper = orderItemMapper;
        this.ledgerService = ledgerService;
        this.mockPaymentGateway = mockPaymentGateway;
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
        payment.setPaymentNo(mockPaymentGateway.prepay(orderNo, request.getAmount()));
        payment.setOrderId(order.getId());
        payment.setOrderNo(orderNo);
        payment.setAmount(request.getAmount());
        payment.setChannel(request.getChannel());
        payment.setThirdStatus("PENDING");
        payment.setStandardStatus("PAYING");
        paymentMapper.insert(payment);
        return payment;
    }

    /**
     * 支付回调：置订单已支付 + 生成取餐码。
     * 注意：分账在「核销」时执行（未消费不入账）。
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderDTO handleCallback(String orderNo, String transactionId) {
        Order order = orderService.lockForUpdate(orderNo);

        if (OrderService.STATUS_PAID.equals(order.getStatus())
                || OrderService.STATUS_VERIFIED.equals(order.getStatus())) {
            log.info("duplicate callback ignored, orderNo={}", orderNo);
            return orderService.toDTO(order, loadItems(order.getId()));
        }
        if (!OrderService.STATUS_CREATED.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单状态不可支付: " + order.getStatus());
        }

        LocalDateTime now = LocalDateTime.now();
        orderService.markPaid(order, now);

        Payment payment = paymentMapper.selectOne(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getOrderNo, orderNo).orderByDesc(Payment::getId).last("limit 1"));
        if (payment != null) {
            payment.setThirdStatus("SUCCESS");
            payment.setStandardStatus("PAID");
            payment.setTransactionId(transactionId);
            payment.setCallbackTime(now);
            paymentMapper.updateById(payment);
        }
        log.info("order paid, orderNo={} pickupCode={}", orderNo, order.getPickupCode());
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
}

