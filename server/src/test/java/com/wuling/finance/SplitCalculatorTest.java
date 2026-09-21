package com.wuling.finance;

import com.wuling.finance.entity.SplitRule;
import com.wuling.finance.service.SplitCalculator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SplitCalculatorTest {

    private final SplitCalculator calculator = new SplitCalculator();

    private SplitRule defaultRule() {
        SplitRule rule = new SplitRule();
        rule.setPlatformRatio(1000);
        rule.setStoreRatio(5000);
        rule.setChannelRatio(1500);
        rule.setInvestorRatio(1500);
        rule.setSupplierRatio(1000);
        return rule;
    }

    @Test
    void ratiosShouldSumToOneHundredPercent() {
        assertEquals(10000, defaultRule().totalRatio());
    }

    @Test
    void fivePartiesShouldSumToPaidAmount() {
        SplitCalculator.SplitAmount amount = calculator.calc(3000L, 2, true, defaultRule(), 0L);
        long sum = amount.platform() + amount.store() + amount.channel()
                + amount.investor() + amount.supplier();
        assertEquals(3000L, sum, "五方金额之和必须等于实付金额");
    }

    @Test
    void roundingRemainderGoesToPlatform() {
        // 1999 分按万分比拆分会产生除不尽的尾差，尾差应归平台
        SplitCalculator.SplitAmount amount = calculator.calc(1999L, 1, true, defaultRule(), 0L);
        long sum = amount.platform() + amount.store() + amount.channel()
                + amount.investor() + amount.supplier();
        assertEquals(1999L, sum);
        assertEquals(1999L * 5000 / 10000, amount.store());
    }

    @Test
    void withoutChannelChannelShareGoesToPlatform() {
        SplitCalculator.SplitAmount withChannel = calculator.calc(1000L, 1, true, defaultRule(), 0L);
        SplitCalculator.SplitAmount withoutChannel = calculator.calc(1000L, 1, false, defaultRule(), 0L);
        assertEquals(0L, withoutChannel.channel());
        assertEquals(withChannel.channel(), withoutChannel.platform() - withChannel.platform());
    }

    @Test
    void invalidRatioTotalShouldThrow() {
        SplitRule rule = defaultRule();
        rule.setPlatformRatio(2000);
        assertThrows(IllegalStateException.class, () -> calculator.calc(1000L, 1, true, rule, 0L));
    }

    @Test
    void platformCommissionSplitsBonus() {
        SplitCalculator.SplitAmount amount = calculator.calc(1000L, 1, true, defaultRule(), 30L);
        assertEquals(30L, amount.platformCommission());
        assertEquals(amount.platform() - 30L, amount.platformBonus());
    }
    // ---------- 供应商按明细分摊（遗留项补全） ----------

    @Test
    void supplierShareShouldSplitByLineItems() {
        // 两件商品分属不同供应商：1390 + 1490 = 2880
        java.util.List<SplitCalculator.LineItem> items = java.util.List.of(
                new SplitCalculator.LineItem(401L, 1390L),
                new SplitCalculator.LineItem(402L, 1490L));
        SplitCalculator.SplitAmount amount = calculator.calc(2880L, 2, true, defaultRule(), 0L, items);

        long expected = 1390L * 1000 / 10000 + 1490L * 1000 / 10000;
        assertEquals(expected, amount.supplier(), "供应商份额应按明细逐条分摊后汇总");

        long sum = amount.platform() + amount.store() + amount.channel()
                + amount.investor() + amount.supplier();
        assertEquals(2880L, sum, "分摊后五方之和仍须等于实付金额");
    }

    @Test
    void sameSupplierItemsShouldAggregate() {
        java.util.List<SplitCalculator.LineItem> items = java.util.List.of(
                new SplitCalculator.LineItem(401L, 1000L),
                new SplitCalculator.LineItem(401L, 2000L));
        SplitCalculator.SplitAmount amount = calculator.calc(3000L, 2, true, defaultRule(), 0L, items);
        assertEquals(1000L * 1000 / 10000 + 2000L * 1000 / 10000, amount.supplier());
    }

    @Test
    void withoutLineItemsSupplierShareIsZero() {
        SplitCalculator.SplitAmount amount = calculator.calc(2880L, 2, true, defaultRule(), 0L, java.util.List.of());
        assertEquals(0L, amount.supplier(), "无明细时供应商份额为 0，尾差归平台");
        long sum = amount.platform() + amount.store() + amount.channel()
                + amount.investor() + amount.supplier();
        assertEquals(2880L, sum);
    }
}
