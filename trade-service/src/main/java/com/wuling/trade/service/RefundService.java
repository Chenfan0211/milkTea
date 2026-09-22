package com.wuling.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;

import com.wuling.trade.port.SettlementQueryPort;
import com.wuling.common.mq.MqConstants;
import com.wuling.common.mq.MqProducer;
import com.wuling.common.mq.event.OrderRefundedEvent;
import com.wuling.trade.dto.OrderDTO;
import com.wuling.trade.entity.Order;
import com.wuling.trade.entity.Refund;
import com.wuling.trade.mapper.OrderItemMapper;
import com.wuling.trade.mapper.OrderMapper;
import com.wuling.trade.mapper.RefundMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 整单退款（对齐方案 D-07）：
 * - 仅未核销（未产生分账）或已核销但未结算的订单可退；
 * - 已结算（SETTLED）不可退；
 * - 退款同步冲正台账（待结算取消 / 可结算回退）。
 */
@Service
public class RefundService {

    /**
      结算状态常量（与 finance 的 LedgerService 保持一致）。
      第 6 期解耦后本地声明，避免为两个常量依赖 finance 的编译期包。
      注意：取值必须与 finance 侧一致，改动需同步。
    */

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final RefundMapper refundMapper;
    /** 第 7 期：结算状态改走端口（远程调用 server 内部接口，保持同步语义） */
    private final SettlementQueryPort settlementQueryPort;
    private final OrderService orderService;
    /** 第 6 期：冲正改为 MQ 事件，不再直接依赖 finance */
    private final MqProducer mqProducer;

    public RefundService(OrderMapper orderMapper,
                         OrderItemMapper orderItemMapper,
                         RefundMapper refundMapper,
                              SettlementQueryPort settlementQueryPort,
                         OrderService orderService,
                         MqProducer mqProducer) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.refundMapper = refundMapper;
        this.settlementQueryPort = settlementQueryPort;
        this.orderService = orderService;
        this.mqProducer = mqProducer;
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderDTO refund(String orderNo, String reason) {
        Order order = orderService.lockForUpdate(orderNo);

        if (OrderService.STATUS_REFUNDED.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单已退款");
        }
        if (OrderService.STATUS_COMPLETED.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单已完成，不可退款");
        }
        if (OrderService.STATUS_CREATED.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单未支付，无需退款");
        }
        if (!OrderService.STATUS_PAID.equals(order.getStatus())
                && !OrderService.STATUS_VERIFIED.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单状态不可退款: " + order.getStatus());
        }

        // 已进入可结算（钱已计入可用余额）或已结算的订单不可退，避免资金穿透
        // 第 7 期：结算状态改走端口（远程同步查询 server 内部接口）。
        // 必须保持【同步】语义 —— 若改成异步，会出现
        //「钱已进可用余额却仍被退款」的资金穿透。
        SettlementQueryPort.SettlementStatus settlementStatus = settlementQueryPort.query(order.getId());
        if (settlementStatus.isSettled()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单已结算，不可退款");
        }

        LocalDateTime now = LocalDateTime.now();
        Refund refund = new Refund();
        refund.setRefundNo(nextNo());
        refund.setOrderId(order.getId());
        refund.setOrderNo(orderNo);
        refund.setAmount(order.getPaidAmount());
        refund.setStatus("SUCCESS");
        refund.setReason(reason);
        refund.setApplyTime(now);
        refund.setReviewTime(now);
        refund.setCompleteTime(now);
        refundMapper.insert(refund);

        // 已产生分账的（已核销）需要冲正台账。
        // 第 6 期解耦：冲正动作改由 MQ 事件触发 finance 消费，
        // 但「是否已结算」的前置校验仍在上方【同步查询】完成
        // （防资金穿透，不能用事件替代）。
        if (settlementStatus.isHasSettlement()) {
            OrderRefundedEvent event = new OrderRefundedEvent();
            event.setOrderId(order.getId());
            event.setOrderNo(orderNo);
            event.setRefundAmount(order.getPaidAmount());
            event.setRefundNo(refund.getRefundNo());
            mqProducer.send(MqConstants.FINANCE_REVERSE_ROUTING_KEY, event, orderNo);
        }

        orderService.markRefunded(order);
        return orderService.toDTO(order, orderItemMapper.selectList(new LambdaQueryWrapper<com.wuling.trade.entity.OrderItem>()
                .eq(com.wuling.trade.entity.OrderItem::getOrderId, order.getId())));
    }

    private String nextNo() {
        return "RF" + System.currentTimeMillis() % 100000000L
                + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
    }
}

