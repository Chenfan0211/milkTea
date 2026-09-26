package com.wuling.trade.pricing;

import com.wuling.trade.port.MemberLevelPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会员等级解析与折扣查询的<b>失败语义</b>测试。
 *
 * <p>核心关注点：等级/折扣查询属于跨服务调用，可能超时或故障。
 * 此时必须「安全失败」——按不打折处理，而不是抛异常让整单下不了。
 * 因为「不打折」最坏是用户没享到优惠，而「抛异常」是所有人都下不了单。
 */
class MemberPricingFailureModeTest {

    /** 可模拟故障的桩端口 */
    private static class FlakyPort implements MemberLevelPort {
        final Map<String, String> discounts = new HashMap<>();
        String serverLevel;
        boolean throwOnLevel;
        boolean throwOnDiscount;

        @Override
        public String findUserLevelCode(Long userId) {
            if (throwOnLevel) {
                throw new IllegalStateException("user-service 超时");
            }
            return serverLevel;
        }

        @Override
        public String findLevelDiscount(String levelCode) {
            if (throwOnDiscount) {
                throw new IllegalStateException("marketing-service 超时");
            }
            return discounts.get(levelCode);
        }
    }

    @Test
    @DisplayName("等级查询抛异常时不中断下单，按不打折处理")
    void 等级查询故障时不抛异常() {
        FlakyPort port = new FlakyPort();
        port.serverLevel = "Lv1";
        port.discounts.put("Lv1", "8折");
        port.throwOnLevel = true;

        MemberPricingService service = new MemberPricingService(port);

        // 不应抛出：查询故障必须被吞掉并安全兜底
        String level = service.resolveLevelCode(9L, null);
        assertThat(level).as("服务端查询失败且客户端未传 -> 无等级").isNull();
        assertThat(service.memberPrice(1600L, level)).as("无等级 -> 原价，不得误打折").isEqualTo(1600L);
    }

    @Test
    @DisplayName("等级查询故障时回落到客户端传入值（保证不阻塞下单）")
    void 等级查询故障回落客户端值() {
        FlakyPort port = new FlakyPort();
        port.throwOnLevel = true;
        MemberPricingService service = new MemberPricingService(port);

        String level = service.resolveLevelCode(9L, "Lv1");
        assertThat(level).as("服务端不可用时才回落到客户端值").isEqualTo("Lv1");
    }

    @Test
    @DisplayName("折扣查询抛异常时按不打折处理，不抛异常")
    void 折扣查询故障时不抛异常() {
        FlakyPort port = new FlakyPort();
        port.serverLevel = "Lv1";
        port.discounts.put("Lv1", "8折");
        port.throwOnDiscount = true;
        MemberPricingService service = new MemberPricingService(port);

        assertThat(service.discountOf("Lv1")).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(service.memberPrice(1600L, "Lv1")).isEqualTo(1600L);
    }

    @Test
    @DisplayName("服务端有等级时以服务端为准，客户端等级仅记录不采信")
    void 服务端等级优先() {
        FlakyPort port = new FlakyPort();
        port.serverLevel = "Lv1";
        port.discounts.put("Lv1", "8折");
        port.discounts.put("Lv3", "6折");
        MemberPricingService service = new MemberPricingService(port);

        // 客户端谎报 Lv3，仍须解析出服务端的 Lv1
        assertThat(service.resolveLevelCode(9L, "Lv3")).isEqualTo("Lv1");
        assertThat(service.memberPrice(1600L, service.resolveLevelCode(9L, "Lv3"))).isEqualTo(1280L);
    }

    @Test
    @DisplayName("服务端等级为空字符串时同样视为无等级")
    void 空等级视为无等级() {
        FlakyPort port = new FlakyPort();
        port.serverLevel = "   ";
        MemberPricingService service = new MemberPricingService(port);

        assertThat(service.resolveLevelCode(9L, null)).isNull();
        assertThat(service.memberPrice(1600L, null)).isEqualTo(1600L);
    }
}