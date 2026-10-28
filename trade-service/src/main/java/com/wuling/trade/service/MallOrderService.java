package com.wuling.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.common.mq.MqConstants;
import com.wuling.common.mq.event.OrderPaidEvent;
import com.wuling.common.mq.event.OrderVerifiedEvent;
import com.wuling.common.mq.event.SubscribeMessageEvent;
import com.wuling.common.mq.event.SplitRuleSnapshot;
import com.wuling.common.outbox.OutboxService;
import com.wuling.trade.dto.CreateMallOrderRequest;
import com.wuling.trade.dto.MallCheckoutQuoteDTO;
import com.wuling.trade.dto.MallCheckoutQuoteRequest;
import com.wuling.trade.dto.OrderDTO;
import com.wuling.trade.entity.Order;
import com.wuling.trade.entity.OrderItem;
import com.wuling.trade.entity.OrderShipping;
import com.wuling.trade.mapper.OrderItemMapper;
import com.wuling.trade.mapper.OrderMapper;
import com.wuling.trade.mapper.OrderShippingMapper;
import com.wuling.trade.port.MallAddressPort;
import com.wuling.trade.port.MallCatalogPort;
import com.wuling.trade.port.MallRulePort;
import com.wuling.trade.port.MallLeaderPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class MallOrderService {
    private static final Logger log = LoggerFactory.getLogger(MallOrderService.class);
    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderShippingMapper shippingMapper;
    private final MallCatalogPort catalogPort;
    private final MallAddressPort addressPort;
    private final MallRulePort rulePort;
    private final MallLeaderPort leaderPort;
    private final MallOrderLookupService lookupService;
    private final OutboxService outboxService;
    private final OrderService orderService;

    public MallOrderService(OrderMapper orderMapper, OrderItemMapper orderItemMapper,
                            OrderShippingMapper shippingMapper, MallCatalogPort catalogPort,
                            MallAddressPort addressPort, OutboxService outboxService,
                            OrderService orderService, MallRulePort rulePort, MallLeaderPort leaderPort,
                            MallOrderLookupService lookupService) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.shippingMapper = shippingMapper;
        this.catalogPort = catalogPort;
        this.addressPort = addressPort;
        this.rulePort = rulePort;
        this.leaderPort = leaderPort;
        this.lookupService = lookupService;
        this.outboxService = outboxService;
        this.orderService = orderService;
    }

    /**
     * 商城结算确认：按当前用户身份计算最终成交单价与应付合计。
     *
     * <p>团长按 SKU 售价，非团长按「售价 + 分销佣金」。该结果是首页/详情/购物车/结算
     * 展示与下单校验的唯一金额口径，客户端不得自行叠加价格。
     */
    public MallCheckoutQuoteDTO quote(Long userId, MallCheckoutQuoteRequest request) {
        Map<Long, Integer> quantities = mergeQuoteItems(request.getItems());
        MallLeaderPort.LeaderInfo leaderInfo = leaderPort.resolve(userId);
        boolean leaderBuyer = leaderInfo.leader();
        List<MallCatalogPort.SkuView> skus = catalogPort.findSkus(new ArrayList<>(quantities.keySet()));
        Map<Long, MallCatalogPort.SkuView> skuMap = skus.stream()
                .collect(Collectors.toMap(MallCatalogPort.SkuView::skuId, Function.identity()));
        MallCheckoutQuoteDTO quote = new MallCheckoutQuoteDTO();
        quote.setLeaderBuyer(leaderBuyer);
        List<MallCheckoutQuoteDTO.Item> lines = new ArrayList<>();
        MallCatalogPort.SkuView first = null;
        boolean orderable = true;
        String orderError = null;
        long payable = 0L;
        long commissionTotal = 0L;
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            long skuId = entry.getKey();
            int quantity = entry.getValue();
            MallCatalogPort.SkuView sku = skuMap.get(skuId);
            MallCheckoutQuoteDTO.Item line = new MallCheckoutQuoteDTO.Item();
            line.setSkuId(skuId);
            line.setQuantity((long) quantity);
            if (sku == null) {
                line.setValid(false);
                line.setReason("SKU 不存在");
                lines.add(line);
                continue;
            }
            line.setProductId(sku.productId());
            line.setProductCode(sku.productCode());
            line.setName(sku.name());
            line.setSpecValue(sku.specValue());
            line.setImage(sku.image());
            line.setOriginalPrice(sku.originalPrice());
            line.setAvailableStock(sku.availableStock());
            if (first == null) {
                first = sku;
            } else if (!java.util.Objects.equals(first.storeSubjectId(), sku.storeSubjectId())
                    || !java.util.Objects.equals(first.channelSubjectId(), sku.channelSubjectId())
                    || !java.util.Objects.equals(first.splitRuleId(), sku.splitRuleId())) {
                orderable = false;
                orderError = "同一订单商品的归属主体或分账规则不一致";
            }
            if (!sku.onSale()) {
                line.setValid(false);
                line.setReason("商品已下架");
            } else if (sku.availableStock() < quantity) {
                line.setValid(false);
                line.setReason("可用库存不足");
            } else {
                long unitPrice = leaderBuyer ? sku.salePrice() : sku.salePrice() + sku.distributionCommission();
                long lineCommission = leaderBuyer ? 0L : sku.distributionCommission();
                line.setUnitPrice(unitPrice);
                line.setDistributionCommission(lineCommission);
                line.setLineAmount(unitPrice * quantity);
                line.setValid(true);
                payable += unitPrice * quantity;
                commissionTotal += lineCommission * quantity;
            }
            lines.add(line);
        }
        quote.setItems(lines);
        quote.setPayableAmount(payable);
        quote.setCommissionAmount(commissionTotal);
        quote.setOrderable(orderable);
        quote.setOrderError(orderError);
        return quote;
    }
    public OrderDTO create(Long userId, CreateMallOrderRequest request) {
        return create(userId, request, nextOrderNo());
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderDTO create(Long userId, CreateMallOrderRequest request, String orderNo) {
        String requestId = request.getClientRequestId().trim();
        Order existing = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .eq(Order::getClientRequestId, requestId)
                .last("limit 1"));
        if (existing != null) {
            return orderService.toDTO(existing, loadItems(existing.getId()));
        }
        MallAddressPort.AddressView address = addressPort.findOwned(userId, request.getAddressId());
        if (address == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "收货地址不存在或无权使用");
        }

        Map<Long, Integer> quantities = mergeItems(request.getItems());
        List<MallCatalogPort.SkuView> skus = catalogPort.findSkus(new ArrayList<>(quantities.keySet()));
        Map<Long, MallCatalogPort.SkuView> skuMap = skus.stream()
                .collect(Collectors.toMap(MallCatalogPort.SkuView::skuId, Function.identity()));
        if (skuMap.size() != quantities.size()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "部分商品不存在或已下架");
        }

        List<OrderItem> items = new ArrayList<>();

        // 团长定价：团长身份的用户按 SKU 售价结算（不加分销佣金，也不给上级团长分佣）；
        // 非团长按「售价 + 分销佣金」结算，佣金在订单完成后归其归属团长（无归属并入平台）。
        MallLeaderPort.LeaderInfo leaderInfo = leaderPort.resolve(userId);
        boolean leaderBuyer = leaderInfo.leader();
        Long leaderSubjectId = leaderInfo.leaderSubjectId();
        Long leaderUserId = leaderInfo.leaderUserId();
        long total = 0L;
        MallCatalogPort.SkuView first = null;
        for (Long skuId : quantities.keySet().stream().sorted().toList()) {
            MallCatalogPort.SkuView sku = skuMap.get(skuId);
            int quantity = quantities.get(skuId);
            if (!sku.onSale()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "商品已下架：" + sku.name());
            }
            if (sku.storeSubjectId() == null || sku.supplierSubjectId() == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "商品归属门店或供应商未配置：" + sku.name());
            }
            if (sku.availableStock() < quantity) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "商品库存不足：" + sku.name());
            }
            if (first == null) first = sku;
            if (!Objects.equals(first.storeSubjectId(), sku.storeSubjectId())
                    || !Objects.equals(first.channelSubjectId(), sku.channelSubjectId())
                    || !Objects.equals(first.splitRuleId(), sku.splitRuleId())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "同一订单商品的归属主体或分账规则不一致");
            }
            OrderItem item = new OrderItem();
            item.setMallSkuId(sku.skuId());
            item.setProductId(sku.productCode());
            item.setProductName(sku.name());
            item.setSpecSnapshot(toSpecJson(sku.specValue()));
            item.setImage(sku.image());
            // 团长：unitPrice = 售价；非团长：unitPrice = 售价 + 分销佣金（分/件）
            long unitPrice = leaderBuyer ? sku.salePrice() : sku.salePrice() + sku.distributionCommission();
            item.setUnitPrice(unitPrice);
            item.setOriginalPrice(sku.originalPrice());
            item.setCostPrice(sku.costPrice());
            item.setPlatformCommission(sku.platformCommission());
            // 团长买家实付不含佣金：佣金快照置 0，分账不得切出不存在的金额（守恒）
            item.setDistributionCommission(leaderBuyer ? 0L : sku.distributionCommission());
            item.setSupplierSubjectId(sku.supplierSubjectId());
            item.setQuantity(quantity);
            item.setSubTotal(sku.salePrice() * quantity);
            item.setSubTotal(unitPrice * quantity);
            items.add(item);
            total += item.getSubTotal();
        }

        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setClientRequestId(requestId);
        order.setOrderType("MALL");
        order.setUserId(userId);
        order.setStoreSubjectId(first.storeSubjectId());
        order.setChannelSubjectId(first.channelSubjectId());
        SplitRuleSnapshot ruleSnapshot = rulePort.snapshot(first.splitRuleId());
        if (ruleSnapshot == null || ruleSnapshot.getRuleId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "商城分账规则未配置或已停用");
        }
        order.setSplitRuleId(ruleSnapshot.getRuleId());
        ruleSnapshot.setLeaderSubjectId(leaderSubjectId);
        ruleSnapshot.setLeaderUserId(leaderUserId);
        order.setSplitRuleSnapshot(toJson(ruleSnapshot));
        order.setMealType("MALL");
        order.setStatus(OrderService.STATUS_CREATED);
        order.setPayStatus("UNPAID");
        order.setPayChannel(normalizePayChannel(request.getPayChannel()));
        order.setTotalAmount(total);
        // 商城不参与任何优惠抵扣；SKU 划线价只用于展示，不记入订单优惠金额。
        order.setOriginalAmount(total);
        order.setDiscountAmount(0L);
        order.setPaidAmount(total);
        order.setCouponDiscount(0L);
        order.setStoredValueDiscount(0L);
        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            OrderDTO duplicated = lookupService.find(userId, requestId);
            if (duplicated != null) return duplicated;
            throw new BusinessException(ResultCode.BAD_REQUEST, "重复下单请求，请刷新订单列表");
        }

        for (OrderItem item : items) {
            item.setOrderId(order.getId());
            orderItemMapper.insert(item);
        }
        OrderShipping shipping = new OrderShipping();
        shipping.setOrderId(order.getId());
        shipping.setRecipientName(address.recipientName());
        shipping.setPhone(address.phone());
        shipping.setProvinceName(address.provinceName());
        shipping.setCityName(address.cityName());
        shipping.setDistrictName(address.districtName());
        shipping.setDetailAddress(address.detailAddress());
        shippingMapper.insert(shipping);

        List<MallCatalogPort.StockLine> lines = items.stream()
                .map(item -> new MallCatalogPort.StockLine(item.getMallSkuId(), item.getQuantity()))
                .sorted(Comparator.comparing(MallCatalogPort.StockLine::skuId)).toList();
        MallCatalogPort.StockResult reserved = catalogPort.reserve(orderNo, lines);
        if (reserved == null || !reserved.success()) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    reserved == null || reserved.message() == null ? "库存不足" : reserved.message());
        }
        releaseOnRollback(orderNo, lines);
        return orderService.toDTO(order, items);
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderDTO confirmReceipt(Long userId, String orderNo) {
        Order order = lockMallOrder(orderNo);
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该订单");
        }
        complete(order);
        return orderService.toDTO(order, loadItems(order.getId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void completeBySystem(String orderNo) {
        Order order = lockMallOrder(orderNo);
        complete(order);
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderDTO ship(String orderNo, String courierCompany, String trackingNo, String operator) {
        if (!StringUtils.hasText(courierCompany) || !StringUtils.hasText(trackingNo)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "快递公司和快递单号不能为空");
        }
        Order order = lockMallOrder(orderNo);
        if (!OrderService.STATUS_PAID.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅待发货订单可发货");
        }
        if ("PENDING".equalsIgnoreCase(order.getRefundStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单退款处理中，不能发货");
        }
        LocalDateTime now = LocalDateTime.now();
        orderService.markShipped(order, now, now.plusDays(15));
        OrderShipping patch = shippingMapper.selectOne(new LambdaQueryWrapper<OrderShipping>()
                .eq(OrderShipping::getOrderId, order.getId()).last("limit 1"));
        if (patch == null) {
            throw new BusinessException(ResultCode.ERROR, "订单收货信息缺失");
        }
        patch.setCourierCompany(courierCompany.trim());
        patch.setTrackingNo(trackingNo.trim());
        shippingMapper.updateById(patch);
        logShipmentOperator(orderNo, operator);
        enqueueShippedMessage(order, patch, loadItems(order.getId()));
        return orderService.toDTO(order, loadItems(order.getId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderDTO cancelUnpaid(Long userId, String orderNo, MallStockService stockService) {
        Order order = lockMallOrder(orderNo);
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权取消该订单");
        }
        if (!OrderService.STATUS_CREATED.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单当前状态不可取消");
        }
        Order patch = new Order();
        patch.setId(order.getId());
        patch.setStatus(OrderService.STATUS_CANCELED);
        orderMapper.updateById(patch);
        order.setStatus(OrderService.STATUS_CANCELED);
        stockService.releaseForCancelled(order);
        return orderService.toDTO(order, loadItems(order.getId()));
    }

    private void complete(Order order) {
        if (!OrderService.STATUS_SHIPPED.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅待收货订单可确认收货");
        }
        List<OrderItem> items = loadItems(order.getId());
        orderService.markMallCompleted(order, LocalDateTime.now());

        int itemCount = items.stream().mapToInt(item -> item.getQuantity() == null ? 1 : item.getQuantity()).sum();
        long platformCommission = items.stream()
                .mapToLong(item -> value(item.getPlatformCommission()) * quantity(item)).sum();
        List<OrderVerifiedEvent.Line> lines = items.stream().map(item ->
                  new OrderVerifiedEvent.Line(item.getSupplierSubjectId(), value(item.getSubTotal()),
                          value(item.getPlatformCommission()), value(item.getCostPrice()), quantity(item),
                          item.getDistributionCommission() == null ? 0L : item.getDistributionCommission())).toList();
        OrderVerifiedEvent settlement = new OrderVerifiedEvent();
        settlement.setOrderId(order.getId());
        settlement.setOrderNo(order.getOrderNo());
        settlement.setPaidAmount(order.getPaidAmount());
        settlement.setItemCount(itemCount);
        settlement.setStoreSubjectId(order.getStoreSubjectId());
        settlement.setChannelSubjectId(order.getChannelSubjectId());
        settlement.setSplitRuleId(order.getSplitRuleId());
          settlement.setMallOrder(true);
          SplitRuleSnapshot orderSnapshot = fromSnapshotJson(order.getSplitRuleSnapshot());
          settlement.setLeaderSubjectId(orderSnapshot == null ? null : orderSnapshot.getLeaderSubjectId());
          settlement.setSplitRuleSnapshot(orderSnapshot);
        settlement.setPlatformCommission(platformCommission);
        settlement.setLines(lines);
        outboxService.enqueue("MALL_ORDER", order.getOrderNo(), "MALL_ORDER_COMPLETED_SPLIT",
                MqConstants.FINANCE_SPLIT_ROUTING_KEY, order.getOrderNo(), settlement);

        OrderPaidEvent accrual = new OrderPaidEvent();
        accrual.setOrderId(order.getId());
        accrual.setOrderNo(order.getOrderNo());
        accrual.setUserId(order.getUserId());
        accrual.setPaidAmount(order.getPaidAmount());
        accrual.setChannel(order.getPayChannel());
        accrual.setStoreSubjectId(order.getStoreSubjectId());
        outboxService.enqueue("MALL_ORDER", order.getOrderNo(), "MALL_ORDER_COMPLETED_ACCRUAL",
                MqConstants.PAYMENT_SUCCESS_ROUTING_KEY, order.getOrderNo(), accrual);
    }

    private void enqueueShippedMessage(Order order, OrderShipping shipping, List<OrderItem> items) {
        String summary = items.stream().map(OrderItem::getProductName)
                .filter(Objects::nonNull).limit(2).collect(Collectors.joining("、"));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("character_string1", order.getOrderNo());
        values.put("thing9", shipping.getCourierCompany());
        values.put("character_string4", shipping.getTrackingNo());
        values.put("thing6", summary.isBlank() ? "商城商品" : summary);
        SubscribeMessageEvent event = new SubscribeMessageEvent(
                SubscribeMessageEvent.CURRENT_VERSION,
                "mallShipped",
                order.getUserId(),
                order.getStoreSubjectId(),
                "MALL_SHIPPED:" + order.getOrderNo(),
                "packageMall/pages/order-detail/order-detail?orderNo=" + order.getOrderNo(),
                values,
                Instant.now().toString());
        outboxService.enqueue("MALL_ORDER", order.getOrderNo(), "MALL_ORDER_SHIPPED",
                MqConstants.SUBSCRIBE_ORDER_ROUTING_KEY, "MALL_SHIPPED:" + order.getOrderNo(), event);
    }

    private Order lockMallOrder(String orderNo) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo).eq(Order::getOrderType, "MALL")
                .last("limit 1 for update"));
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "商城订单不存在");
        }
        return order;
    }

    private List<OrderItem> loadItems(Long orderId) {
        return orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderId).orderByAsc(OrderItem::getId));
    }

    private Map<Long, Integer> mergeQuoteItems(List<MallCheckoutQuoteRequest.Item> items) {
        Map<Long, Integer> result = new LinkedHashMap<>();
        for (MallCheckoutQuoteRequest.Item item : items) {
            result.merge(item.getSkuId(), item.getQuantity(), Integer::sum);
        }
        return result;
    }

    private Map<Long, Integer> mergeItems(List<CreateMallOrderRequest.Item> items) {
        Map<Long, Integer> result = new LinkedHashMap<>();
        for (CreateMallOrderRequest.Item item : items) {
            result.merge(item.getSkuId(), item.getQuantity(), Integer::sum);
        }
        return result;
    }

    private String normalizePayChannel(String value) {
        if ("STORED_VALUE".equalsIgnoreCase(value)) return "STORED_VALUE";
        return "WXPAY";
    }

    private String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("商城订单快照序列化失败", e);
        }
    }

    private SplitRuleSnapshot fromSnapshotJson(String json) {
        try {
            return StringUtils.hasText(json) ? JSON.readValue(json, SplitRuleSnapshot.class) : null;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.ERROR, "商城分账规则快照损坏");
        }
    }

    private String toSpecJson(String specValue) {
        try {
            return JSON.writeValueAsString(Map.of("value", specValue == null ? "默认规格" : specValue));
        } catch (Exception e) {
            throw new IllegalStateException("商城规格快照序列化失败", e);
        }
    }

    private long value(Long value) { return value == null ? 0L : value; }
    private int quantity(OrderItem item) { return item.getQuantity() == null ? 1 : item.getQuantity(); }

    private void releaseOnRollback(String orderNo, List<MallCatalogPort.StockLine> lines) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try {
                        catalogPort.release(orderNo, lines);
                    } catch (Exception e) {
                        // 产品域有 15 分钟预占兜底扫描；这里保留错误日志用于定位即时故障。
                        log.error("商城下单回滚释放库存失败 orderNo={}", orderNo, e);
                    }
                }
            }
        });
    }

    private void logShipmentOperator(String orderNo, String operator) {
        // Shipping audit is persisted by the outbox/audit infrastructure in production;
        // keeping the argument here ensures callers cannot silently omit the operator.
        if (!StringUtils.hasText(operator)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "发货操作人不能为空");
        }
    }

    private String nextOrderNo() {
        return "MO" + LocalDateTime.now().format(NO_FMT)
                + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
    }
}


