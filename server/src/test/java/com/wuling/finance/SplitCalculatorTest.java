package com.wuling.finance;

import com.wuling.finance.entity.SplitRule;
import com.wuling.finance.service.SplitCalculator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 分账计算（固定金额 + 成本直给模型）测试。
 *
 * <p>口径：
 * 门店 = 门店每件提成 × 件数；资源方 = 有渠道 ? 资源方每件提成 × 件数 : 0；
 * 供应商 = Σ(商品成本价 × 件数)；平台提成 = Σ(平台提成单价 × 件数)；
 * 投资人 = max(0, 实付 − 门店 − 资源方 − 供应商 − 平台提成) × 投资人比例/10000；
 * 平台 = 尾差（可为负）。
 */
class SplitCalculatorTest {

    private final SplitCalculator calculator = new SplitCalculator();

    private SplitRule defaultRule() {
        SplitRule rule = new SplitRule();
        rule.setStoreRatio(300);     // 门店每件 3 元
        rule.setChannelRatio(100);   // 资源方每件 1 元
        rule.setInvestorRatio(1500); // 投资人 15%
        return rule;
    }

    private SplitCalculator.LineItem item(long supplier, long amount, long commission, long cost) {
        return new SplitCalculator.LineItem(supplier, amount, commission, cost, 1);
    }

    private SplitCalculator.LineItem item(long supplier, long amount, long commission, long cost, int quantity) {
        return new SplitCalculator.LineItem(supplier, amount, commission, cost, quantity);
    }

    @Test
    void storeAndChannelAreFixedPerItem() {
        // 实付 2000 分，2 件，有渠道：门店 300*2=600，资源方 100*2=200
        SplitRule rule = defaultRule();
        SplitCalculator.SplitAmount amount = calculator.calc(2000L, 2, true, rule, 0L, List.of());
        assertEquals(600L, amount.store());
        assertEquals(200L, amount.channel());
    }

    @Test
    void withoutChannelChannelShareIsZero() {
        SplitRule rule = defaultRule();
        SplitCalculator.SplitAmount with = calculator.calc(2000L, 2, true, rule, 0L, List.of());
        SplitCalculator.SplitAmount without = calculator.calc(2000L, 2, false, rule, 0L, List.of());
        assertEquals(0L, without.channel());
        // 无渠道时资源方份额归零，投资人基础额变大、平台尾差也变大
        assertEquals(0L, without.channel());
        assertEquals(with.channel(), 200L, "有渠道时资源方每件提成 = 100 × 2 件");
    }

    @Test
    void supplierIsSumOfCostPriceTimesQuantity() {
        // 两条明细各 1 件：成本 400 + 600 = 1000
        SplitRule rule = defaultRule();
        List<SplitCalculator.LineItem> items = List.of(
                item(401L, 1000L, 100L, 400L),
                item(402L, 1000L, 100L, 600L));
        SplitCalculator.SplitAmount amount = calculator.calc(2000L, 2, true, rule, 0L, items);
        assertEquals(1000L, amount.supplier(), "供应商成本 = 商品成本价 × 件数 汇总");
    }

    @Test
    void platformCommissionIsSumOfLineItems() {
        SplitRule rule = defaultRule();
        List<SplitCalculator.LineItem> items = List.of(
                item(401L, 1000L, 100L, 400L),
                item(402L, 1000L, 150L, 600L));
        SplitCalculator.SplitAmount amount = calculator.calc(2000L, 2, true, rule, 0L, items);
        assertEquals(250L, amount.platformCommission(), "平台提成 = 各商品提成单价 × 件数 汇总");
    }

    @Test
    void investorIsPercentOfBase() {
        SplitRule rule = defaultRule();
        // 2 件，有渠道：门店 600，资源方 200，无明细（供应商 0，平台提成 0）
        // base = 2000 - 600 - 200 = 1200；投资人 = 1200 * 15% = 180
        SplitCalculator.SplitAmount amount = calculator.calc(2000L, 2, true, rule, 0L, List.of());
        assertEquals(180L, amount.investor());
        // 平台尾差 = 2000 - 600 - 200 - 180 = 1020
        assertEquals(1020L, amount.platform());
    }

    @Test
    void fivePartiesAlwaysSumToPaidAmount() {
        SplitRule rule = defaultRule();
        List<SplitCalculator.LineItem> items = List.of(
                item(401L, 1000L, 100L, 400L),
                item(402L, 1000L, 150L, 600L));
        SplitCalculator.SplitAmount amount = calculator.calc(2000L, 2, true, rule, 0L, items);
        long sum = amount.store() + amount.channel() + amount.supplier()
                + amount.investor() + amount.platform();
        assertEquals(2000L, sum, "门店+资源方+供应商+投资人+平台 必须等于实付");
    }

    @Test
    void supplierAndCommissionAreMultipliedByQuantity() {
        // 明细件数：成本价 400 × 3 件 = 1200；平台提成 100 × 3 = 300。
        // 只加单价会得到 400 / 100，导致成本漏算、平台剩余被高估。
        SplitRule rule = defaultRule();
        List<SplitCalculator.LineItem> items = List.of(item(401L, 3000L, 100L, 400L, 3));
        SplitCalculator.SplitAmount amount = calculator.calc(3000L, 3, false, rule, 0L, items);
        assertEquals(1200L, amount.supplier(), "供应商成本 = 成本单价 × 件数");
        assertEquals(300L, amount.platformCommission(), "平台提成 = 提成单价 × 件数");
    }

    @Test
    void platformIsPaidMinusCostsAndCommissionTimesQuantity() {
        // 实付 1390，1 件：成本 700 + 平台提成 70，门店 300，投资人 15%
        // base = 1390 - 300 - 700 - 70 = 320；投资人 = 48；平台剩余 = 1390 - 300 - 700 - 48 = 342
        SplitRule rule = defaultRule();
        List<SplitCalculator.LineItem> items = List.of(item(401L, 1390L, 70L, 700L, 1));
        SplitCalculator.SplitAmount amount = calculator.calc(1390L, 1, false, rule, 0L, items);
        assertEquals(300L, amount.store());
        assertEquals(700L, amount.supplier());
        assertEquals(70L, amount.platformCommission());
        assertEquals(48L, amount.investor());
        assertEquals(342L, amount.platform(), "平台剩余 = 实付 - 成本合计 - 门店 - 资源方 - 投资人");
    }

    @Test
    void multiItemQuantitiesAccumulateAcrossLines() {
        // 2 条明细：A 2 件（成本 400、提成 100），B 3 件（成本 600、提成 150）
        // 成本合计 = 400×2 + 600×3 = 2600；平台提成 = 100×2 + 150×3 = 650
        SplitRule rule = defaultRule();
        List<SplitCalculator.LineItem> items = List.of(
                item(401L, 2000L, 100L, 400L, 2),
                item(402L, 3000L, 150L, 600L, 3));
        SplitCalculator.SplitAmount amount = calculator.calc(5000L, 5, false, rule, 0L, items);
        assertEquals(2600L, amount.supplier());
        assertEquals(650L, amount.platformCommission());
        long sum = amount.store() + amount.channel() + amount.supplier()
                + amount.investor() + amount.platform();
        assertEquals(5000L, sum, "五方合计必须仍等于实付");
    }

    @Test
    void negativeBaseMeansZeroInvestor() {
        SplitRule rule = defaultRule();
        // 门店 300*3=900，资源方 100*3=300，供应商成本 200×3=600，平台提成 100×3=300
        // base = 1000 - 900 - 300 - 600 - 300 < 0，投资人应为 0，平台尾差为负
        List<SplitCalculator.LineItem> items = List.of(item(401L, 1000L, 100L, 200L, 3));
        SplitCalculator.SplitAmount amount = calculator.calc(1000L, 3, true, rule, 0L, items);
        assertEquals(0L, amount.investor(), "基础额为负时投资人为 0");
    }
}