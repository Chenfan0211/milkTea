package com.wuling.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.finance.entity.SplitSnapshot;
import com.wuling.finance.service.LedgerService;
import com.wuling.trade.dto.OrderDTO;
import com.wuling.trade.dto.VerifyRequest;
import com.wuling.trade.entity.Order;
import com.wuling.trade.entity.OrderItem;
import com.wuling.trade.entity.VerifyRecord;
import com.wuling.trade.mapper.OrderItemMapper;
import java.util.ArrayList;
import com.wuling.product.mapper.ProductMapper;
import com.wuling.product.entity.Product;
import com.wuling.finance.service.SplitCalculator;
import com.wuling.marketing.entity.ExchangeOrder;
import com.wuling.marketing.service.PointsService;
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
 * - ORDER：按取餐码 / 订单号匹配已支付订单，核销后订单置 VERIFIED，并触发五方分账；
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
    private final LedgerService ledgerService;
    private final ProductMapper productMapper;
    private final PointsService pointsService;

    public VerifyService(OrderMapper orderMapper,
                         OrderItemMapper orderItemMapper,
                         VerifyRecordMapper verifyRecordMapper,
                         OrderService orderService,
                         LedgerService ledgerService,
                         ProductMapper productMapper,
                         PointsService pointsService) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.verifyRecordMapper = verifyRecordMapper;
        this.orderService = orderService;
        this.ledgerService = ledgerService;
        this.productMapper = productMapper;
        this.pointsService = pointsService;
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
        if (OrderService.STATUS_VERIFIED.equals(order.getStatus())
                || OrderService.STATUS_COMPLETED.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单已核销，请勿重复核销");
        }
        if (!OrderService.STATUS_PAID.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单当前状态不可核销: " + order.getStatus());
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
        List<SplitCalculator.LineItem> lineItems = new ArrayList<>();
        Long firstProductId = null;
        for (OrderItem item : items) {
            Product product = productMapper.selectOne(new LambdaQueryWrapper<Product>()
                    .eq(Product::getProductId, item.getProductId()).last("limit 1"));
            if (product == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在: " + item.getProductId());
            }
            if (firstProductId == null) {
                firstProductId = product.getId();
            }
            long lineAmount = item.getSubTotal() == null ? 0L : item.getSubTotal();
            lineItems.add(new SplitCalculator.LineItem(product.getSupplierSubjectId(), lineAmount));
        }

        SplitSnapshot snapshot = ledgerService.executeSplit(
                order.getId(), order.getOrderNo(), order.getPaidAmount(), items.size(),
                order.getStoreSubjectId(), order.getChannelSubjectId(), firstProductId, 0L, lineItems);

        // 积分兑换产生的自提码核销后回收，保持兑换单状态一致（由兑换单维度处理）

        log.info("verify ok orderNo={} pickupCode={} snapshotNo={}",
                order.getOrderNo(), code, snapshot.getSnapshotNo());

        VerifyResult result = new VerifyResult();
        result.setSuccess(true);
        result.setType("ORDER");
        result.setOrderNo(order.getOrderNo());
        result.setPickupCode(order.getPickupCode());
        result.setAmount(order.getPaidAmount());
        result.setSnapshotNo(snapshot.getSnapshotNo());
        result.setMessage("核销成功");
        return result;
    }

    private VerifyResult verifyExchange(VerifyRequest request) {
        String code = request.getCode().trim();

        // 联动兑换单：核销后置为 VERIFIED（幂等由兑换单状态保证）
        ExchangeOrder exchangeOrder = pointsService.verifyExchange(code);

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


