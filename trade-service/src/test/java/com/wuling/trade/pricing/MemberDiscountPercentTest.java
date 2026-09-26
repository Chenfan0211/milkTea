package com.wuling.trade.pricing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** 新折扣规范：整数百分比表示支付比例（80 = 支付原价的 80%）。 */
class MemberDiscountPercentTest {

    @Test
    @DisplayName("整数百分比与百分号都按除以 100 解析")
    void 百分比折扣解析() {
        assertThat(MemberDiscount.parse("80")).isEqualByComparingTo(new BigDecimal("0.8"));
        assertThat(MemberDiscount.parse("70%")).isEqualByComparingTo(new BigDecimal("0.7"));
        assertThat(MemberDiscount.parse("1")).isEqualByComparingTo(new BigDecimal("0.01"));
        assertThat(MemberDiscount.parse("100%")).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    @DisplayName("百分比边界值按不打折兜底")
    void 百分比边界兜底() {
        assertThat(MemberDiscount.parse("0")).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(MemberDiscount.parse("-1")).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(MemberDiscount.parse("101")).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(MemberDiscount.parse("101%")).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(MemberDiscount.parse("abc")).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    @DisplayName("会员价按支付比例计算，18 元 80% 为 14.4 元")
    void 百分比会员价() {
        assertThat(MemberDiscount.memberPrice(1800L, MemberDiscount.parse("80"))).isEqualTo(1440L);
        assertThat(MemberDiscount.memberPrice(1800L, MemberDiscount.parse("70%"))).isEqualTo(1260L);
    }
}