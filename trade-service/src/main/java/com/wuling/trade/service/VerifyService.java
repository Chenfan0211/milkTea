package com.wuling.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;


import com.wuling.trade.dto.OrderDTO;
import com.wuling.trade.dto.VerifyRequest;
import com.wuling.trade.entity.Order;
import com.wuling.trade.entity.OrderItem;
import com.wuling.trade.entity.VerifyRecord;
import com.wuling.trade.mapper.OrderItemMapper;
import java.util.ArrayList;
import com.wuling.trade.port.ProductQueryPort;


import com.wuling.common.mq.MqConstants;
import com.wuling.common.mq.event.OrderVerifiedEvent;
import com.wuling.common.mq.MqProducer;
import com.wuling.trade.mapper.OrderMapper;
import com.wuling.trade.mapper.VerifyRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 门店核销。
 * 规则（对齐 docs/data-schema.md）：
 * - ORDER：按取餐码 / 订单号匹配已支付订单，核销后订单置 COMPLETED，并触发五方分账；
 * - EXCHANGE：兑换类核销不联动订单表，仅写核销记录。
 * 幂等：通过订单行锁 + 状态机保证重复核销被拒绝。
 */
@Service
public class VerifyService {

    private static final Logger log = LoggerFactory.getLogger(VerifyService.class);

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final VerifyRecordMapper verifyRecordMapper;
    private final OrderService orderService;

    /** 第 6 期：商品查询改走端口 */
    private final ProductQueryPort productQueryPort;
    /** 通过 MQ 通知 marketing 服务核销兑换单（第 5 期解耦） */
    private final MqProducer mqProducer;

    public VerifyService(OrderMapper orderMapper,
                         OrderItemMapper orderItemMapper,
                         VerifyRecordMapper verifyRecordMapper,
                         OrderService orderService,
                         ProductQueryPort productQueryPort,
                         MqProducer mqProducer) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.verifyRecordMapper = verifyRecordMapper;
        this.orderService = orderService;

        this.productQueryPort = productQueryPort;
        this.mqProducer = mqProducer;
    }

    @Transactional(rollbackFor = Exception.class)
    public VerifyResult verify(VerifyRequest request) {
        String type = StringUtils.hasText(request.getType()) ? request.getType() : "ORDER";
        if ("EXCHANGE".equalsIgnoreCase(type)) {
            return verifyExchange(request);
        }
        return verifyOrder(request);
    }

    private VerifyResult verifyOrder(VerifyRequest request) {
        String code = request.getCode().trim();
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getPickupCode, code)
                .orderByDesc(Order::getId)
                .last("limit 1 for update"));
        if (order == null) {
            order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                    .eq(Order::getOrderNo, code)
                    .last("limit 1 for update"));
        }
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "核销码无效或订单不存在");
        }
        if (OrderService.STATUS_COMPLETED.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单已核销，请勿重复核销");
        }
        if (!OrderService.STATUS_PAID.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单当前状态不可核销: " + order.getStatus());
        }

        if ("PENDING".equalsIgnoreCase(order.getRefundStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单退款中，不可核销");
        }

        List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, order.getId()));

        orderService.markVerified(order, LocalDateTime.now());

        VerifyRecord record = new VerifyRecord();
        record.setVerifyCode(code);
        record.setOrderId(order.getId());
        record.setOrderNo(order.getOrderNo());
        record.setType("ORDER");
        record.setStoreSubjectId(order.getStoreSubjectId());
        record.setOperator(request.getOperator());
        record.setDevice(request.getDevice());
        record.setResult("success");
        verifyRecordMapper.insert(record);

        // 核销即消费：触发五方分账快照 + 待结算台账（供应商份额按明细分摊）
        //
        // 件数口径：costPrice / platformCommission 都是「单价（分/件）」。
        // 若不下发 quantity，finance 侧会按 1 件计算，导致成本合计漏算、
        // 平台提成少扣，平台剩余被高估，故此处把明细件数一并带出。
        List<OrderVerifiedEvent.Line> lineItems = new ArrayList<>();
        Long firstProductId = null;
        int totalQuantity = 0;
        for (OrderItem item : items) {
            ProductQueryPort.ProductView product = productQueryPort.findProduct(item.getProductId());
            if (product == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在: " + item.getProductId());
            }
            if (firstProductId == null) {
                firstProductId = product.getId();
            }
            int quantity = item.getQuantity() == null || item.getQuantity() <= 0 ? 1 : item.getQuantity();
            totalQuantity += quantity;
            long lineAmount = item.getSubTotal() == null ? 0L : item.getSubTotal();
            lineItems.add(new OrderVerifiedEvent.Line(
                    product.getSupplierSubjectId(), lineAmount,
                    product.getPlatformCommission(), product.getCostPrice(),
                    quantity));
        }

        // 第 6 期解耦：不再直接调用 finance 的 LedgerService.executeSplit，
        // 改为发布「核销分账」事件，由 finance 服务消费并执行五方分账。
        //
        // 一致性说明：核销记录与订单状态已在上方落库（门店据此放行），
        // 分账异步执行、最终一致；executeSplit 自身幂等 + 消费端幂等双保险。
        OrderVerifiedEvent event = new OrderVerifiedEvent();
        event.setOrderId(order.getId());
        event.setOrderNo(order.getOrderNo());
        event.setPaidAmount(order.getPaidAmount());
        // itemCount 必须是「商品件数」（Σ quantity）而非明细条数：
        // 门店/资源方份额按每件提成 × 件数计，用条数会把多件订单的分成算少。
        event.setItemCount(totalQuantity);
        event.setStoreSubjectId(order.getStoreSubjectId());
        event.setChannelSubjectId(order.getChannelSubjectId());
        event.setFirstProductId(firstProductId);
        event.setPlatformCommission(0L);
        event.setLines(lineItems);
        mqProducer.send(MqConstants.FINANCE_SPLIT_ROUTING_KEY, event, order.getOrderNo());

        // 积分兑换产生的自提码核销后回收，保持兑换单状态一致（由兑换单维度处理）

        log.info("verify ok orderNo={} pickupCode={} (分账已异步投递)",
                order.getOrderNo(), code);

        VerifyResult result = new VerifyResult();
        result.setSuccess(true);
        result.setType("ORDER");
        result.setOrderNo(order.getOrderNo());
        result.setPickupCode(order.getPickupCode());
        result.setAmount(order.getPaidAmount());
        // 分账改为异步后，核销即时无法拿到快照号；
        // 该字段保留为 null，前端不应依赖它（已在此注明）。
        result.setSnapshotNo(null);
        result.setMessage("核销成功");
        return result;
    }

    private VerifyResult verifyExchange(VerifyRequest request) {
        String code = request.getCode().trim();

        // 第 5 期解耦：不再直接调用 marketing 的 PointsService，
        // 改为发布「兑换核销」事件，由 marketing 服务消费并更新兑换单状态。
        // 幂等由消费端的 MqIdempotent 保证（重复投递不会重复核销）。
        mqProducer.send(MqConstants.EXCHANGE_VERIFY_ROUTING_KEY,
                java.util.Map.of("pickupCode", code), code);

        VerifyRecord record = new VerifyRecord();
        record.setVerifyCode(code);
        record.setOrderNo(code);
        record.setType("EXCHANGE");
        record.setOperator(request.getOperator());
        record.setDevice(request.getDevice());
        record.setResult("success");
        verifyRecordMapper.insert(record);

        VerifyResult result = new VerifyResult();
        result.setSuccess(true);
        result.setType("EXCHANGE");
        result.setOrderNo(code);
        result.setPickupCode(code);
        result.setAmount(0L);
        result.setMessage("兑换核销成功");
        return result;
    }

    public List<OrderDTO> pendingPool(Long storeSubjectId) {
        return orderService.pendingVerifyOrders(storeSubjectId);
    }

    public List<VerifyRecord> records(Long storeSubjectId) {
        LambdaQueryWrapper<VerifyRecord> query = new LambdaQueryWrapper<VerifyRecord>()
                .orderByDesc(VerifyRecord::getId);
        if (storeSubjectId != null) {
            query.eq(VerifyRecord::getStoreSubjectId, storeSubjectId);
        }
        return verifyRecordMapper.selectList(query);
    }

    /** 核销结果 */
    public static class VerifyResult {
        private boolean success;
        private String type;
        private String orderNo;
        private String pickupCode;
        private Long amount;
        private String snapshotNo;
        private String message;

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getOrderNo() { return orderNo; }
        public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
        public String getPickupCode() { return pickupCode; }
        public void setPickupCode(String pickupCode) { this.pickupCode = pickupCode; }
        public Long getAmount() { return amount; }
        public void setAmount(Long amount) { this.amount = amount; }
        public String getSnapshotNo() { return snapshotNo; }
        public void setSnapshotNo(String snapshotNo) { this.snapshotNo = snapshotNo; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}


