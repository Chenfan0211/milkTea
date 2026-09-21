package com.wuling.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wuling.common.api.PageResult;
import com.wuling.common.mq.MqConstants;
import com.wuling.common.mq.MqProducer;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.product.entity.Product;
import com.wuling.product.entity.ProductStore;
import com.wuling.product.mapper.ProductMapper;
import com.wuling.product.mapper.ProductStoreMapper;
import com.wuling.subject.entity.BizSubject;
import com.wuling.subject.mapper.BizSubjectMapper;
import com.wuling.trade.dto.CreateOrderRequest;
import com.wuling.trade.dto.OrderDTO;
import com.wuling.trade.entity.Order;
import com.wuling.trade.entity.OrderItem;
import com.wuling.trade.mapper.OrderItemMapper;
import com.wuling.trade.mapper.OrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_VERIFIED = "VERIFIED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_REFUNDED = "REFUNDED";
    public static final String STATUS_CANCELED = "CANCELED";

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductMapper productMapper;
    private final ProductStoreMapper productStoreMapper;
    private final BizSubjectMapper bizSubjectMapper;
    private final MqProducer mqProducer;

    public OrderService(OrderMapper orderMapper,
                        OrderItemMapper orderItemMapper,
                        ProductMapper productMapper,
                        ProductStoreMapper productStoreMapper,
                        BizSubjectMapper bizSubjectMapper,
                        MqProducer mqProducer) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.productMapper = productMapper;
        this.productStoreMapper = productStoreMapper;
        this.bizSubjectMapper = bizSubjectMapper;
        this.mqProducer = mqProducer;
    }

    // ---------- 下单 ----------

    @Transactional(rollbackFor = Exception.class)
    public OrderDTO createOrder(CreateOrderRequest request) {
        BizSubject store = bizSubjectMapper.selectById(request.getStoreSubjectId());
        if (store == null || !"STORE".equals(store.getSubjectType())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "门店不存在");
        }

        long total = 0L;
        long original = 0L;
        List<OrderItem> items = new ArrayList<>();

        for (CreateOrderRequest.Item reqItem : request.getItems()) {
            Product product = productMapper.selectOne(new LambdaQueryWrapper<Product>()
                    .eq(Product::getProductId, reqItem.getProductId()));
            if (product == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在: " + reqItem.getProductId());
            }
            if (product.getOnSale() == null || product.getOnSale() != 1) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "商品已下架: " + product.getName());
            }
            // ②B：未绑定供应商的商品不允许下单（避免付款后核销分账失败）
            if (product.getSupplierSubjectId() == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "商品未配置供应商，暂不可下单: " + product.getName());
            }
            requireProductInStore(product.getId(), request.getStoreSubjectId());

            int quantity = reqItem.getQuantity() == null ? 1 : reqItem.getQuantity();
            long unitPrice = product.getPrice() == null ? 0L : product.getPrice();
            long originalPrice = product.getOriginalPrice() == null ? unitPrice : product.getOriginalPrice();

            OrderItem item = new OrderItem();
            item.setProductId(product.getProductId());
            item.setProductName(product.getName());
            item.setSpecSnapshot(reqItem.getSpec());
            item.setUnitPrice(unitPrice);
            item.setOriginalPrice(originalPrice);
            item.setQuantity(quantity);
            item.setSubTotal(unitPrice * quantity);
            items.add(item);

            total += unitPrice * quantity;
            original += originalPrice * quantity;
        }

        Order order = new Order();
        order.setOrderNo(nextOrderNo());
        order.setUserId(request.getUserId());
        order.setStoreSubjectId(request.getStoreSubjectId());
        order.setChannelSubjectId(resolveChannel(request.getStoreSubjectId(), request.getChannelSubjectId()));
        order.setMealType(request.getMealType());
        order.setStatus(STATUS_CREATED);
        order.setPayStatus("UNPAID");
        order.setTotalAmount(total);
        order.setOriginalAmount(original);
        order.setDiscountAmount(Math.max(0L, original - total));
        order.setPaidAmount(total);
        order.setCouponDiscount(0L);
        order.setPointsUsed(0L);
        order.setPointsEarned(0L);
        order.setRemark(request.getRemark());
        orderMapper.insert(order);

        for (OrderItem item : items) {
            item.setOrderId(order.getId());
            orderItemMapper.insert(item);
        }

        // 发送延迟消息：15 分钟未支付则自动关闭（MQ 基础设施示例用法）
        try {
            mqProducer.sendDelay(MqConstants.ORDER_TIMEOUT_ROUTING_KEY, order.getOrderNo(), order.getOrderNo());
        } catch (Exception e) {
            // MQ 不可用不应阻塞下单，记录日志由补偿任务兜底
            log.warn("订单超时消息发送失败 orderNo={} err={}", order.getOrderNo(), e.getMessage());
        }
        return toDTO(order, items);
    }

    // ---------- 查询 ----------

    public OrderDTO getByOrderNo(String orderNo) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        return toDTO(order, loadItems(order.getId()));
    }

    public PageResult<OrderDTO> pageOrders(long current, long size, String status, String search) {
        LambdaQueryWrapper<Order> query = new LambdaQueryWrapper<Order>().orderByDesc(Order::getId);
        if (StringUtils.hasText(status)) {
            query.eq(Order::getStatus, status);
        }
        if (StringUtils.hasText(search)) {
            query.and(w -> w.like(Order::getOrderNo, search).or().like(Order::getPickupCode, search));
        }
        Page<Order> page = orderMapper.selectPage(new Page<>(current, size), query);
        List<OrderDTO> records = page.getRecords().stream()
                .map(o -> toDTO(o, loadItems(o.getId())))
                .toList();
        return PageResult.of(records, page.getCurrent(), page.getSize(), page.getTotal());
    }

    /** 核销池：已支付待核销的订单 */
    public List<OrderDTO> pendingVerifyOrders(Long storeSubjectId) {
        LambdaQueryWrapper<Order> query = new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, STATUS_PAID)
                .orderByAsc(Order::getId);
        if (storeSubjectId != null) {
            query.eq(Order::getStoreSubjectId, storeSubjectId);
        }
        return orderMapper.selectList(query).stream()
                .map(o -> toDTO(o, loadItems(o.getId())))
                .toList();
    }

    // ---------- 状态流转（供支付/核销/退款服务调用，均在事务内） ----------

    @Transactional(rollbackFor = Exception.class)
    public Order lockForUpdate(String orderNo) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo).last("for update"));
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        return order;
    }

    public int updateStatus(Order order, String status) {
        Order patch = new Order();
        patch.setId(order.getId());
        patch.setStatus(status);
        return orderMapper.updateById(patch);
    }

    public void markPaid(Order order, LocalDateTime payTime) {
        Order patch = new Order();
        patch.setId(order.getId());
        patch.setStatus(STATUS_PAID);
        patch.setPayStatus("PAID");
        patch.setPayTime(payTime);
        patch.setPickupCode(nextPickupCode(order.getStoreSubjectId()));
        orderMapper.updateById(patch);
        order.setStatus(STATUS_PAID);
        order.setPayStatus("PAID");
        order.setPayTime(payTime);
        order.setPickupCode(patch.getPickupCode());
    }

    public void markVerified(Order order, LocalDateTime verifyTime) {
        Order patch = new Order();
        patch.setId(order.getId());
        patch.setStatus(STATUS_VERIFIED);
        patch.setVerifyTime(verifyTime);
        orderMapper.updateById(patch);
        order.setStatus(STATUS_VERIFIED);
        order.setVerifyTime(verifyTime);
    }

    public void markRefunded(Order order) {
        Order patch = new Order();
        patch.setId(order.getId());
        patch.setStatus(STATUS_REFUNDED);
        patch.setRefundStatus("REFUNDED");
        orderMapper.updateById(patch);
        order.setStatus(STATUS_REFUNDED);
        order.setRefundStatus("REFUNDED");
    }

    public void markRefundPending(Order order) {
        Order patch = new Order();
        patch.setId(order.getId());
        patch.setRefundStatus("PENDING");
        orderMapper.updateById(patch);
        order.setRefundStatus("PENDING");
    }

    // ---------- 内部工具 ----------

    private void requireProductInStore(Long productId, Long storeSubjectId) {
        Long count = productStoreMapper.selectCount(new LambdaQueryWrapper<ProductStore>()
                .eq(ProductStore::getProductId, productId)
                .eq(ProductStore::getStoreSubjectId, storeSubjectId));
        if (count == null || count == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该门店未上架此商品");
        }
    }

    private Long resolveChannel(Long storeSubjectId, Long channelSubjectId) {
        if (channelSubjectId != null) {
            return channelSubjectId;
        }
        Long bound = orderMapper.selectBoundChannel(storeSubjectId);
        return bound;
    }

    private List<OrderItem> loadItems(Long orderId) {
        return orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderId)
                .orderByAsc(OrderItem::getId));
    }

    private String nextOrderNo() {
        return "WX" + LocalDateTime.now().format(NO_FMT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    private String nextPickupCode(Long storeSubjectId) {
        Long count = orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                .eq(Order::getStoreSubjectId, storeSubjectId)
                .isNotNull(Order::getPickupCode));
        long seq = (count == null ? 0 : count) + 1;
        return String.format("%04d", seq % 10000);
    }


    /**
     * 未支付则关闭订单（供 MQ 超时消费者调用）。
     *
     * 幂等与并发安全：
     * - 使用行锁读取，避免与支付回调并发时状态错乱；
     * - 仅当状态为 CREATED 且未支付时才关闭；
     * - 已支付/已关闭/已核销等状态一律不动，返回 false。
     *
     * @return true=本次关闭了订单；false=无需关闭（已支付或已关闭）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean closeIfUnpaid(String orderNo) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo).last("for update"));
        if (order == null) {
            return false;
        }
        if (!STATUS_CREATED.equals(order.getStatus()) || !"UNPAID".equals(order.getPayStatus())) {
            return false;
        }
        Order patch = new Order();
        patch.setId(order.getId());
        patch.setStatus(STATUS_CANCELED);
        patch.setRemark("超时未支付，系统自动关闭");
        return orderMapper.updateById(patch) > 0;
    }

    public OrderDTO toDTO(Order order, List<OrderItem> items) {
        OrderDTO dto = new OrderDTO();
        dto.setId(order.getId());
        dto.setOrderNo(order.getOrderNo());
        dto.setUserId(order.getUserId());
        dto.setUser("U" + order.getUserId());
        dto.setStoreSubjectId(order.getStoreSubjectId());
        BizSubject store = order.getStoreSubjectId() == null ? null : bizSubjectMapper.selectById(order.getStoreSubjectId());
        dto.setStore(store == null ? "-" : store.getName());
        dto.setMealType(order.getMealType());
        dto.setStatus(order.getStatus());
        dto.setPayStatus(order.getPayStatus());
        dto.setPickupCode(order.getPickupCode());
        dto.setTotalAmount(order.getTotalAmount());
        dto.setOriginalAmount(order.getOriginalAmount());
        dto.setDiscountAmount(order.getDiscountAmount());
        dto.setPaidAmount(order.getPaidAmount());
        dto.setRefundStatus(order.getRefundStatus());
        dto.setCreateTime(fmt(order.getCreateTime()));
        dto.setPayTime(fmt(order.getPayTime()));
        dto.setVerifyTime(fmt(order.getVerifyTime()));
        dto.setCompleteTime(fmt(order.getCompleteTime()));
        Map<String, Integer> merged = new LinkedHashMap<>();
        Map<String, OrderDTO.Item> itemMap = new LinkedHashMap<>();
        for (OrderItem item : items) {
            OrderDTO.Item dtoItem = new OrderDTO.Item();
            dtoItem.setProductId(item.getProductId());
            dtoItem.setName(item.getProductName());
            dtoItem.setSpec(item.getSpecSnapshot());
            dtoItem.setUnitPrice(item.getUnitPrice());
            dtoItem.setOriginalPrice(item.getOriginalPrice());
            dtoItem.setQuantity(item.getQuantity());
            dtoItem.setSubTotal(item.getSubTotal());
            itemMap.put(item.getProductId() + "#" + item.getId(), dtoItem);
            merged.merge(item.getProductName(), item.getQuantity() == null ? 0 : item.getQuantity(), Integer::sum);
        }
        dto.setItems(new ArrayList<>(itemMap.values()));
        dto.setSummary(merged.entrySet().stream()
                .map(e -> e.getKey() + " x" + e.getValue())
                .collect(Collectors.joining(",")));
        return dto;
    }

    private String fmt(LocalDateTime time) {
        return time == null ? null : time.format(FMT);
    }
}


