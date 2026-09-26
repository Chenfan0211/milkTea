package com.wuling.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wuling.common.api.PageResult;
import com.wuling.common.mq.MqConstants;
import com.wuling.common.mq.MqProducer;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;





import com.wuling.trade.pricing.MemberPriceCheck;
import com.wuling.trade.pricing.MemberPricingService;
import com.wuling.trade.port.ProductQueryPort;
import com.wuling.trade.port.SplitSnapshotQueryPort;
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
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_CANCELED = "CANCELED";

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    /** 第 6 期：product/subject 的只读查询改走端口，解除跨域编译依赖 */
    private final ProductQueryPort productQueryPort;

    /**
     * 分账快照只读端口：后台订单接口据此回填「分账明细」。
     *
     * <p>split_snapshot 归属 finance 域，trade 不直接读表；
     * 未核销订单查不到快照，dto.split 为 null，前端会提示暂无快照。
     */
    private final SplitSnapshotQueryPort splitSnapshotQueryPort;


    private final MqProducer mqProducer;

    /**
     * 会员价计算与校验（第 15 期）。
     *
     * <p>放在独立 service 而非本类私有方法：计价规则需要被单测直接覆盖，
     * 混在下单主流程（含 MQ、分账、事务）里很难单独验证。
     */
    private final MemberPricingService memberPricingService;

    public OrderService(OrderMapper orderMapper,
                        OrderItemMapper orderItemMapper,
                        ProductQueryPort productQueryPort,
                        SplitSnapshotQueryPort splitSnapshotQueryPort,
                        MqProducer mqProducer,
                        MemberPricingService memberPricingService) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.productQueryPort = productQueryPort;
        this.splitSnapshotQueryPort = splitSnapshotQueryPort;
        this.mqProducer = mqProducer;
        this.memberPricingService = memberPricingService;
    }

    // ---------- 下单 ----------

    @Transactional(rollbackFor = Exception.class)
    public OrderDTO createOrder(CreateOrderRequest request) {
        // 第 6 期：改走端口查询（实现可替换为 Feign，业务代码不变）
        String storeName = productQueryPort.findSubjectName(request.getStoreSubjectId());
        if (storeName == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "门店不存在");
        }


        // 会员等级：以服务端记录为准，客户端传入值仅作交叉校验（见 MemberPricingService）
        String levelCode = memberPricingService.resolveLevelCode(request.getUserId(), request.getVipLevel());

        long total = 0L;
        long original = 0L;
        List<OrderItem> items = new ArrayList<>();

        for (CreateOrderRequest.Item reqItem : request.getItems()) {
            ProductQueryPort.ProductView product = productQueryPort.findProduct(reqItem.getProductId());
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
            // 打折基准是「商品原价」而非 product.price：
            // product.price 本身就是「会员价基数」（seed 里 1390 分，前端显示 ¥13.9），
            // 再乘折扣会变成折上折。原价（original_price，1600 分 / ¥16）才是门市价。
            long listPrice = product.getOriginalPrice() == null ? 0L : product.getOriginalPrice();
            // 会员单价 = 原价 × 等级折扣（无等级/未知等级时折扣为 1，即原价）
            long unitPrice = memberPricingService.memberPrice(listPrice, levelCode);
            long originalPrice = listPrice;

            OrderItem item = new OrderItem();
            item.setProductId(product.getProductId());
            item.setProductName(product.getName());
            item.setSpecSnapshot(reqItem.getSpec());
            item.setImage(product.getImage());
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

        // 关键业务日志：下单成功（金额为「分」，便于对账核对）
        log.info("订单创建成功 orderNo={} userId={} storeSubjectId={} items={} 实付={}分 应付={}分 等级={}",
                order.getOrderNo(), order.getUserId(), order.getStoreSubjectId(),
                items.size(), order.getPaidAmount(), order.getTotalAmount(), levelCode);

        // 发送延迟消息：15 分钟未支付则自动关闭（MQ 基础设施示例用法）
        try {
            mqProducer.sendDelay(MqConstants.ORDER_TIMEOUT_ROUTING_KEY, order.getOrderNo(), order.getOrderNo());
        } catch (Exception e) {
            // MQ 不可用不应阻塞下单，记录日志由补偿任务兜底
            log.warn("订单超时消息发送失败 orderNo={} err={}", order.getOrderNo(), e.getMessage());
        }

        // 把「客户端算的价对不对」带回给前端：金额以服务端为准，
        // 但前端需要知道自己是否口径漂移，否则会长期「显示一个价、实收另一个价」。
        MemberPriceCheck check = memberPricingService.verify(request.getClientAmount(), total);
        OrderDTO dto = toDTO(order, items);
        dto.setPriceCheck(toPriceCheckDTO(check));
        return dto;
    }

    /** 校验结果 -> 响应 DTO（响应层不直接暴露 pricing 包的类型，避免耦合到接口契约）。 */
    private static OrderDTO.PriceCheck toPriceCheckDTO(MemberPriceCheck check) {
        OrderDTO.PriceCheck dto = new OrderDTO.PriceCheck();
        dto.setClientAmount(check.clientAmount());
        dto.setServerAmount(check.serverAmount());
        dto.setCorrect(check.correct());
        dto.setReason(check.reason());
        return dto;
    }

    // ---------- 查询 ----------

    public OrderDTO getByOrderNo(String orderNo) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        // byAdmin=true：后台订单详情要展示「金额与分账」
        return toDTO(order, loadItems(order.getId()), true);
    }

    /**
     * 我的订单（分页）：在数据库层按 userId 过滤。
     * 原实现是「取全表前 N 条再内存过滤」，会导致分页结果失真（前 N 条都属于他人时自己看不到订单）。
     */
    public PageResult<OrderDTO> pageOrdersByUser(Long userId, long current, long size) {
        Page<Order> page = orderMapper.selectPage(
                new Page<>(current, size),
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getUserId, userId)
                        .orderByDesc(Order::getId));
        List<OrderDTO> records = page.getRecords().stream()
                .map(o -> toDTO(o, loadItems(o.getId())))
                .toList();
        return PageResult.of(records, page.getCurrent(), page.getSize(), page.getTotal());
    }

    public PageResult<OrderDTO> pageOrders(long current, long size, String status, String search, Long storeSubjectId) {
        LambdaQueryWrapper<Order> query = new LambdaQueryWrapper<Order>().orderByDesc(Order::getId);
        if (StringUtils.hasText(status)) {
            query.eq(Order::getStatus, status);
        }
        // 门店可搜索下拉框：按门店主体 id 精确过滤
        if (storeSubjectId != null) {
            query.eq(Order::getStoreSubjectId, storeSubjectId);
        }
        if (StringUtils.hasText(search)) {
            query.and(w -> w.like(Order::getOrderNo, search).or().like(Order::getPickupCode, search));
        }
        Page<Order> page = orderMapper.selectPage(new Page<>(current, size), query);
        List<OrderDTO> records = page.getRecords().stream()
                // byAdmin=true：后台列表要展示「分账明细」，需附上分账快照
                .map(o -> toDTO(o, loadItems(o.getId()), true))
                .toList();
        return PageResult.of(records, page.getCurrent(), page.getSize(), page.getTotal());
    }

    /** 核销池：已支付待核销的订单 */
    public List<OrderDTO> pendingVerifyOrders(Long storeSubjectId) {
        LambdaQueryWrapper<Order> query = new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, STATUS_PAID)
                .and(w -> w.isNull(Order::getRefundStatus)
                        .or().ne(Order::getRefundStatus, "PENDING"))
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
        patch.setStatus(STATUS_COMPLETED);
        patch.setVerifyTime(verifyTime);
        orderMapper.updateById(patch);
        order.setStatus(STATUS_COMPLETED);
        order.setVerifyTime(verifyTime);
    }

    public void markRefunded(Order order) {
        Order patch = new Order();
        patch.setId(order.getId());
        patch.setStatus(STATUS_CANCELED);
        patch.setRefundStatus("REFUNDED");
        orderMapper.updateById(patch);
        order.setStatus(STATUS_CANCELED);
        order.setRefundStatus("REFUNDED");
    }

    public void markRefundPending(Order order) {
        Order patch = new Order();
        patch.setId(order.getId());
        patch.setRefundStatus("PENDING");
        orderMapper.updateById(patch);
        order.setRefundStatus("PENDING");
    }

    public void markRefundFailed(Order order) {
        Order patch = new Order();
        patch.setId(order.getId());
        // 退款失败只恢复退款状态，不能把已核销订单从 COMPLETED 降回 PAID。
        patch.setStatus(STATUS_COMPLETED.equals(order.getStatus()) ? STATUS_COMPLETED : STATUS_PAID);
        patch.setRefundStatus("FAILED");
        orderMapper.updateById(patch);
        order.setStatus(patch.getStatus());
        order.setRefundStatus("FAILED");
    }

    // ---------- 内部工具 ----------

    private void requireProductInStore(Long productId, Long storeSubjectId) {
        if (!productQueryPort.isProductInStore(productId, storeSubjectId)) {
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


    /**
     * 用户主动取消「未支付」订单。
     *
     * <p>与 {@link #closeIfUnpaid(String)}（系统超时关闭）的区别：
     * <ul>
     *   <li><b>校验订单归属</b>：只能取消自己的订单，防止越权取消他人订单；</li>
     *   <li>备注记录为用户主动取消，便于审计区分超时关闭。</li>
     * </ul>
     *
     * <p>并发安全：行锁读取；仅 CREATED + UNPAID 才可取消，
     * 已支付订单应走退款流程（见 RefundService），此处直接拒绝。
     *
     * @param orderNo  订单号
     * @param userId   当前登录用户 ID（取自 JWT）
     * @return 取消后的订单详情
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderDTO cancelUnpaid(String orderNo, Long userId) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo).last("for update"));
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        if (userId != null && !userId.equals(order.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权取消该订单");
        }
        if (STATUS_CANCELED.equals(order.getStatus())) {
            // 幂等：已取消直接返回当前状态，避免重复取消报错
            return toDTO(order, loadItems(order.getId()));
        }
        if (!STATUS_CREATED.equals(order.getStatus()) || !"UNPAID".equals(order.getPayStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单当前状态不可取消，请走退款流程");
        }
        Order patch = new Order();
        patch.setId(order.getId());
        patch.setStatus(STATUS_CANCELED);
        patch.setRemark("用户主动取消");
        orderMapper.updateById(patch);
        order.setStatus(STATUS_CANCELED);
        return toDTO(order, loadItems(order.getId()));
    }

    public OrderDTO toDTO(Order order, List<OrderItem> items) {
        return toDTO(order, items, false);
    }

    /**
     * 订单 -> DTO。
     *
     * @param byAdmin 是否按「后台口径」组装：为 true 时附加分账快照（split）
     *                与商品成本/提成单价，供运营后台的「分账明细」展示；
     *                为 false（小程序查询）时跳过，避免给 C 端链路增加跨服务调用。
     */
    public OrderDTO toDTO(Order order, List<OrderItem> items, boolean byAdmin) {
        OrderDTO dto = new OrderDTO();
        dto.setId(order.getId());
        dto.setOrderNo(order.getOrderNo());
        dto.setUserId(order.getUserId());
        dto.setUser("U" + order.getUserId());
        dto.setStoreSubjectId(order.getStoreSubjectId());
        String storeName = productQueryPort.findSubjectName(order.getStoreSubjectId());
        dto.setStore(storeName == null ? "-" : storeName);
        dto.setMealType(order.getMealType());
        dto.setStatus(order.getStatus());
        dto.setPayStatus(order.getPayStatus());
        // 核销码口径：只有「已支付待核销」的订单才下发取餐码。
        // 待支付尚未生成；已核销/已完成/已取消/已退款即使库里有历史值也不再对外暴露，
        // 保证后台与小程序任一出口的核销码展示口径一致。
        dto.setPickupCode(STATUS_PAID.equals(order.getStatus()) ? order.getPickupCode() : null);
        // 门店订单固定来源分类，供小程序订单页页签过滤
        dto.setCategory("store");
        dto.setTotalAmount(order.getTotalAmount());
        dto.setOriginalAmount(order.getOriginalAmount());
        dto.setDiscountAmount(order.getDiscountAmount());
        dto.setPaidAmount(order.getPaidAmount());
        dto.setCouponDiscount(order.getCouponDiscount());
        dto.setRefundStatus(order.getRefundStatus());
        dto.setCreateTime(fmt(order.getCreateTime()));
        dto.setPayTime(fmt(order.getPayTime()));
        dto.setVerifyTime(fmt(order.getVerifyTime()));
        dto.setCompleteTime(fmt(order.getCompleteTime()));
        Map<String, Integer> merged = new LinkedHashMap<>();
        Map<String, OrderDTO.Item> itemMap = new LinkedHashMap<>();
        for (OrderItem item : items) {
            OrderDTO.Item dtoItem = new OrderDTO.Item();
            dtoItem.setId(item.getId());
            dtoItem.setProductId(item.getProductId());
            dtoItem.setName(item.getProductName());
            dtoItem.setSpec(item.getSpecSnapshot());
            dtoItem.setImage(item.getImage());
            dtoItem.setUnitPrice(item.getUnitPrice());
            dtoItem.setOriginalPrice(item.getOriginalPrice());
            dtoItem.setQuantity(item.getQuantity());
            dtoItem.setSubTotal(item.getSubTotal());
            if (byAdmin) {
                // 成本价 / 平台提成都是「单价（分/件）」，后台据此按件数复算成本合计
                ProductQueryPort.ProductView product = productQueryPort.findProduct(item.getProductId());
                if (product != null) {
                    dtoItem.setCostPrice(product.getCostPrice());
                    dtoItem.setPlatformCommission(product.getPlatformCommission());
                }
            }
            itemMap.put(item.getProductId() + "#" + item.getId(), dtoItem);
            merged.merge(item.getProductName(), item.getQuantity() == null ? 0 : item.getQuantity(), Integer::sum);
        }
        dto.setItems(new ArrayList<>(itemMap.values()));
        dto.setSummary(merged.entrySet().stream()
                .map(e -> e.getKey() + " x" + e.getValue())
                .collect(Collectors.joining(",")));
        if (byAdmin) {
            dto.setSplit(loadSplitSummary(order.getOrderNo()));
        }
        return dto;
    }

    /**
     * 组装分账明细（未核销 / 查询失败时为 null）。
     *
     * <p>快照里的 costTotal（supplier_amount）与 platformCommission 已是
     * 「单价 × 件数」的合计值，直接透传，前端不得再乘件数。
     */
    private OrderDTO.Split loadSplitSummary(String orderNo) {
        SplitSnapshotQueryPort.SplitSnapshotView view = splitSnapshotQueryPort.findByOrderNo(orderNo);
        if (view == null) {
            return null;
        }
        OrderDTO.Split split = new OrderDTO.Split();
        split.setSnapshotNo(view.getSnapshotNo());
        split.setItemCount(view.getItemCount());
        split.setCostTotal(view.getCostTotal());
        split.setStoreShare(view.getStoreShare());
        split.setChannelShare(view.getChannelShare());
        split.setInvestorShare(view.getInvestorShare());
        split.setPlatformCommission(view.getPlatformCommission());
        split.setPlatformShare(view.getPlatformShare());
        split.setBase(view.getBase());
        return split;
    }

    private String fmt(LocalDateTime time) {
        return time == null ? null : time.format(FMT);
    }
}
