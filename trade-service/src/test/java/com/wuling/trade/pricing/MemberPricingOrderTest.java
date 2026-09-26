package com.wuling.trade.pricing;

import com.wuling.trade.dto.CreateOrderRequest;
import com.wuling.trade.dto.OrderDTO;
import com.wuling.trade.mapper.OrderItemMapper;
import com.wuling.trade.mapper.OrderMapper;
import com.wuling.trade.port.MemberLevelPort;
import com.wuling.trade.port.ProductQueryPort;
import com.wuling.common.mq.MqProducer;
import com.wuling.trade.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 下单会员价端到端测试（第 15 期）。
 *
 * <p><b>为什么不用 @SpringBootTest</b>：本用例只关心「金额怎么算出来的」，
 * 引入完整上下文会连带拉起数据源/MQ/Redis（本地无中间件直接失败），
 * 与测试意图无关。这里用内存桩替换 Mapper 与端口，聚焦计价链路。
 *
 * <p><b>覆盖的关键行为</b>：
 * <ol>
 *   <li>会员价 = 商品<b>原价</b> × 等级折扣（不是 product.price 再打折）；</li>
 *   <li>等级以<b>服务端</b>为准，客户端传高等级不生效（防改包越权）；</li>
 *   <li>客户端金额不一致时，下单金额仍用服务端值，并把 correct=false 回告前端；</li>
 *   <li>无等级/未知等级不误打折。</li>
 * </ol>
 */
class MemberPricingOrderTest {

    /**
     * 用 JDK 动态代理顶替 Mapper：MyBatis 的 BaseMapper 方法极多，
     * 手写实现类既要覆盖全部抽象方法、又会随版本升级而失配；
     * 这里只拦截 insert（补主键并记录），其余方法一律返回默认值。
     */
    @SuppressWarnings("unchecked")
    private static <T> T stubMapper(Class<T> type, List<Object> inserted, long baseId) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> {
                    if ("insert".equals(method.getName()) && args != null && args.length == 1) {
                        Object entity = args[0];
                        // 模拟数据库自增主键：后续逻辑依赖 order.getId()
                        setField(entity, "id", baseId + inserted.size());
                        inserted.add(entity);
                        return 1;
                    }
                    // 其余方法（查询/更新等）本用例不涉及，返回类型安全的默认值
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    return null;
                });
    }

    /** 反射写字段：实体用的是 Lombok @Data，测试里不便直接依赖具体 setter 名 */
    private static void setField(Object target, String fieldName, long value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法设置字段 " + fieldName, e);
        }
    }

    /** 桩端口：可配置「服务端等级」与「等级折扣」 */
    private static class StubMemberLevelPort implements MemberLevelPort {
        final Map<String, String> discounts = new HashMap<>();
        String serverLevel;

        @Override
        public String findUserLevelCode(Long userId) {
            return serverLevel;
        }

        @Override
        public String findLevelDiscount(String levelCode) {
            return discounts.get(levelCode);
        }
    }

    /** 桩商品：seed 里 classic-001 的真实价格（price 1390 / originalPrice 1600） */
    private static class StubProductPort implements ProductQueryPort {
        @Override
        public ProductView findProduct(String productId) {
            ProductView v = new ProductView();
            v.setId(1L);
            v.setProductId(productId);
            v.setName("五窨茉莉抹茶");
            v.setPrice(1390L);          // 会员价基数（前端显示 ¥13.9）
            v.setOriginalPrice(1600L);  // 门市价（前端显示 ¥16，打折基准）
            v.setImage("/assets/images/3x/menu-product.jpg");
            v.setOnSale(1);
            v.setSupplierSubjectId(201L);
            return v;
        }

        @Override
        public boolean isProductInStore(Long productId, Long storeSubjectId) {
            return true;
        }

        @Override
        public String findSubjectName(Long subjectId) {
            return "星沙乐运魔方店";
        }
    }

    private StubMemberLevelPort memberPort;
    private List<Object> insertedOrders;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        memberPort = new StubMemberLevelPort();
        memberPort.discounts.put("Lv1", "8折");
        memberPort.discounts.put("Lv2", "7折");
        memberPort.discounts.put("Lv3", "6折");
        insertedOrders = new ArrayList<>();
        OrderMapper orderMapper = stubMapper(OrderMapper.class, insertedOrders, 1000L);
        OrderItemMapper itemMapper = stubMapper(OrderItemMapper.class, new ArrayList<>(), 2000L);

        // MqProducer 为下单必经依赖；测试只关心计价，这里用子类覆写发送方法，
        // 避免真实连 MQ（本地无 Broker 会导致下单链路抛异常）。
        MqProducer noopMq = new MqProducer(null) {
            @Override
            public void sendDelay(String routingKey, Object payload, String bizKey) {
                // 测试中不投递超时消息
            }
        };

        orderService = new OrderService(
                orderMapper, itemMapper, new StubProductPort(), orderNo -> null, noopMq,
                new MemberPricingService(memberPort));
    }

    private CreateOrderRequest request(String vipLevel, Long clientAmount, int quantity) {
        CreateOrderRequest req = new CreateOrderRequest();
        req.setUserId(9L);
        req.setStoreSubjectId(101L);
        req.setMealType("pickup");
        req.setVipLevel(vipLevel);
        req.setClientAmount(clientAmount);
        CreateOrderRequest.Item item = new CreateOrderRequest.Item();
        item.setProductId("classic-001");
        item.setQuantity(quantity);
        req.setItems(List.of(item));
        return req;
    }

    @Test
    @DisplayName("会员价 = 商品原价 × 等级折扣（1 件 8 折 -> 1600×0.8=1280 分）")
    void 按下单等级折扣计价() {
        memberPort.serverLevel = "Lv1";
        OrderDTO dto = orderService.createOrder(request("Lv1", null, 1));

        assertThat(dto.getOriginalAmount()).as("原价取 original_price=1600").isEqualTo(1600L);
        assertThat(dto.getPaidAmount()).as("8 折后实付 1280").isEqualTo(1280L);
        assertThat(dto.getDiscountAmount()).as("优惠金额 = 原价 - 实付").isEqualTo(320L);
    }

    @Test
    @DisplayName("数量参与计价：2 件 8 折 -> 2560 分")
    void 数量参与计价() {
        memberPort.serverLevel = "Lv1";
        OrderDTO dto = orderService.createOrder(request("Lv1", null, 2));

        assertThat(dto.getPaidAmount()).isEqualTo(2560L);
        assertThat(dto.getOriginalAmount()).isEqualTo(3200L);
    }

    @Test
    @DisplayName("不同等级取到各自折扣，不是恒为同一档")
    void 等级对应折扣生效() {
        memberPort.serverLevel = "Lv3";
        OrderDTO dto = orderService.createOrder(request("Lv3", null, 1));
        // 6 折：1600 × 0.6 = 960
        assertThat(dto.getPaidAmount()).isEqualTo(960L);

        memberPort.discounts.put("Lv2", "7折");
        memberPort.serverLevel = "Lv2";
        OrderDTO dto2 = orderService.createOrder(request("Lv2", null, 1));
        // 7 折：1600 × 0.7 = 1120
        assertThat(dto2.getPaidAmount()).isEqualTo(1120L);
    }

    @Test
    @DisplayName("防改包：客户端传高等级，仍按服务端等级计价")
    void 客户端等级不被采信() {
        memberPort.serverLevel = "Lv1";   // 服务端是 8 折
        OrderDTO dto = orderService.createOrder(request("Lv3", null, 1));

        assertThat(dto.getPaidAmount())
                .as("客户端传 Lv3(6折) 不生效，必须按服务端 Lv1(8折) 计价")
                .isEqualTo(1280L);
    }

    @Test
    @DisplayName("客户端金额正确时回告 correct=true")
    void 客户端金额一致() {
        memberPort.serverLevel = "Lv1";
        OrderDTO dto = orderService.createOrder(request("Lv1", 1280L, 1));

        assertThat(dto.getPriceCheck()).isNotNull();
        assertThat(dto.getPriceCheck().getCorrect()).isTrue();
        assertThat(dto.getPriceCheck().getServerAmount()).isEqualTo(1280L);
        assertThat(dto.getPriceCheck().getReason()).isNull();
    }

    @Test
    @DisplayName("客户端金额偏低时仍按服务端计价，并回告正确金额")
    void 客户端金额偏低() {
        memberPort.serverLevel = "Lv1";
        OrderDTO dto = orderService.createOrder(request("Lv1", 1000L, 1));

        assertThat(dto.getPaidAmount()).as("下单金额必须用服务端值").isEqualTo(1280L);
        assertThat(dto.getPriceCheck().getCorrect()).isFalse();
        assertThat(dto.getPriceCheck().getClientAmount()).isEqualTo(1000L);
        assertThat(dto.getPriceCheck().getServerAmount()).isEqualTo(1280L);
        // reason 里给的是「差额」而不是绝对金额：前端要提示用户差多少，
        // 而不是再报一次总额（总额前端自己已经知道）。
        assertThat(dto.getPriceCheck().getReason()).contains("280");
    }

    @Test
    @DisplayName("客户端金额偏高时同样以服务端为准")
    void 客户端金额偏高() {
        memberPort.serverLevel = "Lv1";
        OrderDTO dto = orderService.createOrder(request("Lv1", 9999L, 1));

        assertThat(dto.getPaidAmount()).isEqualTo(1280L);
        assertThat(dto.getPriceCheck().getCorrect()).isFalse();
    }

    @Test
    @DisplayName("客户端未传金额：不判正确，但仍按服务端计价并说明")
    void 客户端未传金额() {
        memberPort.serverLevel = "Lv1";
        OrderDTO dto = orderService.createOrder(request("Lv1", null, 1));

        assertThat(dto.getPaidAmount()).isEqualTo(1280L);
        assertThat(dto.getPriceCheck().getCorrect()).isFalse();
        assertThat(dto.getPriceCheck().getClientAmount()).isNull();
        assertThat(dto.getPriceCheck().getReason()).isNotNull();
    }

    @Test
    @DisplayName("服务端无等级时不误打折，按原价下单")
    void 无等级不打折() {
        memberPort.serverLevel = null;
        OrderDTO dto = orderService.createOrder(request(null, null, 1));

        assertThat(dto.getPaidAmount()).as("无等级应等于原价").isEqualTo(1600L);
        assertThat(dto.getDiscountAmount()).as("无折扣时优惠为 0").isZero();
    }

    @Test
    @DisplayName("等级存在但折扣配置缺失/脏数据时不误打折")
    void 折扣缺失不打折() {
        memberPort.serverLevel = "LvX";   // 未配置折扣
        OrderDTO dto = orderService.createOrder(request("LvX", null, 1));
        assertThat(dto.getPaidAmount()).isEqualTo(1600L);
    }
}