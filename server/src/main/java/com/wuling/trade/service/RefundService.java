package com.wuling.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.finance.entity.SettlementRecord;
import com.wuling.finance.mapper.SettlementRecordMapper;
import com.wuling.finance.service.LedgerService;
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

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final RefundMapper refundMapper;
    private final SettlementRecordMapper settlementRecordMapper;
    private final OrderService orderService;
    private final LedgerService ledgerService;

    public RefundService(OrderMapper orderMapper,
                         OrderItemMapper orderItemMapper,
                         RefundMapper refundMapper,
                         SettlementRecordMapper settlementRecordMapper,
                         OrderService orderService,
                         LedgerService ledgerService) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.refundMapper = refundMapper;
        this.settlementRecordMapper = settlementRecordMapper;
        this.orderService = orderService;
        this.ledgerService = ledgerService;
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
        Long settled = settlementRecordMapper.selectCount(new LambdaQueryWrapper<SettlementRecord>()
                .eq(SettlementRecord::getOrderId, order.getId())
                .in(SettlementRecord::getStatus,
                        LedgerService.SETTLE_SETTLEABLE,
                        LedgerService.SETTLE_SETTLED));
        if (settled != null && settled > 0) {
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

        // 已产生分账的（已核销）需要冲正台账
        Long snapshotExists = settlementRecordMapper.selectCount(new LambdaQueryWrapper<SettlementRecord>()
                .eq(SettlementRecord::getOrderId, order.getId()));
        if (snapshotExists != null && snapshotExists > 0) {
            ledgerService.reverseForOrder(orderNo);
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

