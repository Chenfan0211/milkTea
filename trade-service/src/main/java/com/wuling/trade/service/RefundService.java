package com.wuling.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.common.mq.MqConstants;
import com.wuling.common.mq.MqProducer;
import com.wuling.common.mq.event.OrderRefundedEvent;
import com.wuling.trade.dto.OrderDTO;
import com.wuling.trade.entity.Order;
import com.wuling.trade.entity.OrderItem;
import com.wuling.trade.entity.Refund;
import com.wuling.trade.mapper.OrderItemMapper;
import com.wuling.trade.mapper.OrderMapper;
import com.wuling.trade.mapper.RefundMapper;
import com.wuling.trade.port.SettlementQueryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 整单退款（对齐方案 D-07 + 退款三态改造）。
 *
 * <p><b>退款状态机（2026-09 改造）</b>：
 * <ul>
 *   <li>{@code REFUNDING} —— 退款中：已受理、钱尚未到账（默认态）；</li>
 *   <li>{@code FAILED} —— 退款失败：同步下单失败或微信回调 ABNORMAL/CLOSED；</li>
 *   <li>{@code SUCCESS} —— 退款成功：微信退款结果通知 SUCCESS。</li>
 * </ul>
 *
 * <p><b>关键资金安全约束</b>：
 * <ol>
 *   <li>退款是<b>异步</b>的：微信接口返回受理成功只代表「已受理」，不代表钱已到账，
 *       最终结果以退款结果通知（{@code WxRefundNotifyController}）为准；</li>
 *   <li>订单主状态受理后保持 <b>PAID</b>：仅置
 *       {@code refund_status=PENDING}；退款成功才置 {@code CANCELED + refund_status=REFUNDED}，失败仍为 PAID；</li>
 *   <li>台账冲正（{@link OrderRefundedEvent}）<b>只在退款成功后发布</b>，
 *       否则「钱还没退、账先冲了」会造成资金对不上；</li>
 *   <li>退款成功后由 finance 按原结算记录逐账户扣回，余额不足时整批回滚并记录对账异常。</li>
 * </ol>
 */
@Service
public class RefundService {

    /** 退款单状态：退款中 */
    public static final String STATUS_REFUNDING = "REFUNDING";
    /** 退款单状态：退款失败 */
    public static final String STATUS_FAILED = "FAILED";
    /** 退款单状态：退款成功 */
    public static final String STATUS_SUCCESS = "SUCCESS";

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final RefundMapper refundMapper;
    private final SettlementQueryPort settlementQueryPort;
    private final OrderService orderService;
    private final PaymentGatewayResolver gatewayResolver;
    private final MqProducer mqProducer;

    public RefundService(OrderMapper orderMapper,
                         OrderItemMapper orderItemMapper,
                         RefundMapper refundMapper,
                         SettlementQueryPort settlementQueryPort,
                         OrderService orderService,
                         PaymentGatewayResolver gatewayResolver,
                         MqProducer mqProducer) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.refundMapper = refundMapper;
        this.settlementQueryPort = settlementQueryPort;
        this.orderService = orderService;
        this.gatewayResolver = gatewayResolver;
        this.mqProducer = mqProducer;
    }

    /**
     * 发起退款（用户取消已支付订单 / 后台退款入口共用）。
     *
     * <p>流程：校验 → 落 REFUNDING 记录 + 订单 refund_status=PENDING（本事务内）→
     * 事务提交后调三方退款下单 → 失败置 FAILED（订单保持退款前状态）。
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderDTO refund(String orderNo, String reason) {
        Order order = orderService.lockForUpdate(orderNo);
        validateRefundable(order);

        LocalDateTime now = LocalDateTime.now();
        Refund refund = new Refund();
        refund.setRefundNo(nextNo());
        refund.setOrderId(order.getId());
        refund.setOrderNo(orderNo);
        refund.setAmount(order.getPaidAmount());
        refund.setStatus(STATUS_REFUNDING);
        refund.setReason(reason);
        refund.setApplyTime(now);
        refundMapper.insert(refund);

        // 订单先标记「退款中」，真正的 REFUNDED 等退款成功回调再置
        orderService.markRefundPending(order);

        // 调用三方退款下单（放本方法尾部：事务仍持有行锁，但下单失败走 FAILED 不抛异常，
        // 避免事务回滚把 REFUNDING 记录也回滚掉 —— 失败也应有记录可查/可重试）
        applyToGateway(refund);

        return orderService.toDTO(order, loadItems(order.getId()));
    }

    /**
     * 调用支付通道发起退款；同步失败置 FAILED（订单保持退款前状态，可重试）。
     */
    private void applyToGateway(Refund refund) {
        PaymentGateway.RefundApplyResult result = gatewayResolver.active()
                .refund(refund.getOrderNo(), refund.getRefundNo(), refund.getAmount(), refund.getReason());

        if (result == null) {
            // mock 通道不支持退款：保持 REFUNDING（由外部/定时补偿推进），不伪造成功
            return;
        }
        if (result.accepted()) {
            if (result.thirdRefundNo() != null) {
                Refund patch = new Refund();
                patch.setId(refund.getId());
                patch.setThirdRefundNo(result.thirdRefundNo());
                refundMapper.updateById(patch);
                refund.setThirdRefundNo(result.thirdRefundNo());
            }
            return;
        }
        markFailed(refund, result.failReason());
    }

    /**
     * 退款结果通知处理：SUCCESS 置成功并完成订单退款与台账冲正；ABNORMAL/CLOSED 置失败。
     *
     * @return true 本次真正推进了状态（首次）；false 已处理过（幂等跳过）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean onRefundResult(String refundNo, boolean success, String failReason) {
        Refund refund = refundMapper.selectOne(new LambdaQueryWrapper<Refund>()
                .eq(Refund::getRefundNo, refundNo));
        if (refund == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "退款单不存在：" + refundNo);
        }
        // 幂等：终态不重复处理
        if (STATUS_SUCCESS.equals(refund.getStatus()) || STATUS_FAILED.equals(refund.getStatus())) {
            return false;
        }
        if (success) {
            markSuccess(refund);
            return true;
        }
        markFailed(refund, failReason);
        return true;
    }

    /**
     * 重新退款：仅退款失败(FAILED)可重试，走同一套下单逻辑。
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderDTO retry(Long refundId) {
        Refund refund = refundMapper.selectById(refundId);
        if (refund == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "退款单不存在");
        }
        if (!STATUS_FAILED.equals(refund.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅退款失败的单据可重新退款");
        }
        Order order = orderMapper.selectById(refund.getOrderId());
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "原订单不存在");
        }
        validateRefundable(order);

        // 重新生成退款单号（微信要求商户退款单号唯一），覆盖旧单继续走三态
        String newRefundNo = nextNo();
        refund.setRefundNo(newRefundNo);
        refund.setStatus(STATUS_REFUNDING);
        refund.setApplyTime(LocalDateTime.now());
        refund.setThirdRefundNo(null);
        refundMapper.updateById(refund);
        orderService.markRefundPending(order);

        applyToGateway(refund);
        return orderService.toDTO(order, loadItems(order.getId()));
    }

    private void markSuccess(Refund refund) {
        Refund patch = new Refund();
        patch.setId(refund.getId());
        patch.setStatus(STATUS_SUCCESS);
        patch.setCompleteTime(LocalDateTime.now());
        refundMapper.updateById(patch);
        refund.setStatus(STATUS_SUCCESS);

        // 订单真正变为已退款 + 台账冲正（仅退款成功后才做）
        Order order = orderMapper.selectById(refund.getOrderId());
        if (order != null) {
            orderService.markRefunded(order);
            // 已产生分账（已核销）需冲正台账；未核销订单无台账，无需冲正
            SettlementQueryPort.SettlementStatus settlementStatus = settlementQueryPort.query(order.getId());
            if (settlementStatus.isHasSettlement()) {
                OrderRefundedEvent event = new OrderRefundedEvent();
                event.setOrderId(order.getId());
                event.setOrderNo(order.getOrderNo());
                event.setRefundAmount(refund.getAmount());
                event.setRefundNo(refund.getRefundNo());
                mqProducer.send(MqConstants.FINANCE_REVERSE_ROUTING_KEY, event, order.getOrderNo());
            }
        }
    }

    private void markFailed(Refund refund, String failReason) {
        Refund patch = new Refund();
        patch.setId(refund.getId());
        patch.setStatus(STATUS_FAILED);
        // 仅在失败原因非空时覆盖，避免把原退款原因（用户取消等）冲掉
        if (failReason != null && !failReason.isBlank()) {
            patch.setReason(failReason.substring(0, Math.min(failReason.length(), 255)));
        }
        refundMapper.updateById(patch);
        refund.setStatus(STATUS_FAILED);

        Order order = orderMapper.selectById(refund.getOrderId());
        if (order != null) {
            orderService.markRefundFailed(order);
        }
    }

    private void validateRefundable(Order order) {
        if (!OrderService.STATUS_PAID.equals(order.getStatus())
                && !OrderService.STATUS_COMPLETED.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单状态不可退款: " + order.getStatus());
        }
        if ("PENDING".equalsIgnoreCase(order.getRefundStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单退款处理中，请勿重复申请");
        }
    }
    private List<OrderItem> loadItems(Long orderId) {
        return orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderId));
    }

    private String nextNo() {
        return "RF" + System.currentTimeMillis() % 100000000L
                + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
    }
}
