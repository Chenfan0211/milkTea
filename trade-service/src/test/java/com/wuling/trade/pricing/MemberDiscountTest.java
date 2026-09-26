package com.wuling.trade.pricing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会员折扣解析测试。
 *
 * <p>核心目的：<b>锁死与小程序端 {@code utils/member-level.js} 的 parseDiscount 同口径</b>。
 * 两端规则一旦漂移，就会出现「前端显示 14.4 元、后端实收 16 元」的客诉。
 * 因此这里逐个复刻前端的行为预期，任一侧改动都会在此暴露。
 */
class MemberDiscountTest {

    @Test
    @DisplayName("中文折扣「N折」按 除以10 解析")
    void 中文折扣解析() {
        assertThat(MemberDiscount.parse("8折")).isEqualByComparingTo(new BigDecimal("0.8"));
        assertThat(MemberDiscount.parse("7折")).isEqualByComparingTo(new BigDecimal("0.7"));
        assertThat(MemberDiscount.parse("6折")).isEqualByComparingTo(new BigDecimal("0.6"));
        // 前端正则允许小数折（(\d+(?:\.\d+)?)\s*折）
        assertThat(MemberDiscount.parse("7.5折")).isEqualByComparingTo(new BigDecimal("0.75"));
    }

    @Test
    @DisplayName("折扣区间非法时按不打折兜底，绝不误打折")
    void 折扣越界兜底() {
        // 0折 = 免费，属脏数据
        assertThat(MemberDiscount.parse("0折")).isEqualByComparingTo(BigDecimal.ONE);
        // >10 折 = 加价，同样拒绝
        assertThat(MemberDiscount.parse("11折")).isEqualByComparingTo(BigDecimal.ONE);
        // 负数
        assertThat(MemberDiscount.parse("-3折")).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    @DisplayName("小数比率写法（0.8）同样支持")
    void 小数比率解析() {
        assertThat(MemberDiscount.parse("0.8")).isEqualByComparingTo(new BigDecimal("0.8"));
        // >1 的比率不合法（会变成加价）
        assertThat(MemberDiscount.parse("1.5")).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    @DisplayName("空值与无法识别的文本一律不打折")
    void 空值与脏数据() {
        assertThat(MemberDiscount.parse(null)).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(MemberDiscount.parse("")).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(MemberDiscount.parse("   ")).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(MemberDiscount.parse("无折扣")).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    @DisplayName("会员价 = 原价 × 折扣，四舍五入到分")
    void 会员价计算() {
        // 与小程序端对齐的实测样例：18 元原价、8 折 -> 14.4 元 = 1440 分
        assertThat(MemberDiscount.memberPrice(1800L, MemberDiscount.parse("8折"))).isEqualTo(1440L);
        assertThat(MemberDiscount.memberPrice(1800L, MemberDiscount.parse("7折"))).isEqualTo(1260L);
        assertThat(MemberDiscount.memberPrice(1800L, MemberDiscount.parse("6折"))).isEqualTo(1080L);
        // 前端实测：17 元 8 折 = 13.6 元
        assertThat(MemberDiscount.memberPrice(1700L, MemberDiscount.parse("8折"))).isEqualTo(1360L);
        // 前端实测：16 元 8 折 = 12.8 元
        assertThat(MemberDiscount.memberPrice(1600L, MemberDiscount.parse("8折"))).isEqualTo(1280L);
    }

    @Test
    @DisplayName("小数折的四舍五入不得产生分位漂移")
    void 小数折舍入() {
        // 1390 × 0.75 = 1042.5 -> 1043（HALF_UP）
        assertThat(MemberDiscount.memberPrice(1390L, MemberDiscount.parse("7.5折"))).isEqualTo(1043L);
        // 用 double 相乘会得到 1042.4999...，这里用 BigDecimal 必须稳定为 1043
        assertThat(MemberDiscount.memberPrice(1390L, new BigDecimal("0.75"))).isEqualTo(1043L);
    }

    @Test
    @DisplayName("原价为空/非法时按 0 处理，不得抛异常")
    void 原价边界() {
        assertThat(MemberDiscount.memberPrice(null, BigDecimal.ONE)).isZero();
        assertThat(MemberDiscount.memberPrice(0L, MemberDiscount.parse("8折"))).isZero();
        assertThat(MemberDiscount.memberPrice(-100L, MemberDiscount.parse("8折"))).isZero();
        assertThat(MemberDiscount.memberPrice(1800L, null)).isEqualTo(1800L);
    }
}