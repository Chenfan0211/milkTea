package com.wuling.finance;

import com.wuling.finance.entity.SplitRule;
import com.wuling.finance.service.SplitCalculator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 投资人「当月累计达标后比例」规则测试（固定金额模型下）。
 *
 * <p>口径：投资人当月累计分账额达到 investorThresholdAmount（分）后，
 * 投资人比例改用 investorRatioAfter（万分比）；阈值 <= 0 或达标比例 <= 0 视为未启用。
 */
class InvestorThresholdTest {

    private final SplitCalculator calculator = new SplitCalculator();

    private SplitRule ruleWithThreshold(long threshold, int investorAfter) {
        SplitRule rule = new SplitRule();
        rule.setStoreRatio(300);
        rule.setChannelRatio(100);
        rule.setInvestorRatio(1500);
        rule.setInvestorThresholdAmount(threshold);
        rule.setInvestorRatioAfter(investorAfter);
        return rule;
    }

    @Test
    void belowThresholdUsesBaseInvestorRatio() {
        SplitRule rule = ruleWithThreshold(100_000L, 2000);
        assertEquals(1500, rule.resolveInvestorRatio(99_999L), "未达标应使用原投资人比例");
    }

    @Test
    void reachingThresholdOnThisOrderUsesAfterRatio() {
        SplitRule rule = ruleWithThreshold(100_000L, 2000);
        assertEquals(2000, rule.resolveInvestorRatio(100_000L), "达标当单应使用达标后比例");
    }

    @Test
    void aboveThresholdUsesAfterRatio() {
        SplitRule rule = ruleWithThreshold(100_000L, 2000);
        assertEquals(2000, rule.resolveInvestorRatio(500_000L));
    }

    @Test
    void zeroThresholdDisablesRule() {
        SplitRule rule = ruleWithThreshold(0L, 2000);
        assertEquals(1500, rule.resolveInvestorRatio(9_999_999L), "阈值为 0 表示不启用，应始终用原比例");
    }

    @Test
    void zeroAfterRatioKeepsBaseRatio() {
        SplitRule rule = ruleWithThreshold(100_000L, 0);
        assertEquals(1500, rule.resolveInvestorRatio(500_000L), "达标后比例为 0 视为未配置，保留原比例");
    }

    @Test
    void nullAccumulatedTreatedAsZero() {
        SplitRule rule = ruleWithThreshold(100_000L, 2000);
        assertEquals(1500, rule.resolveInvestorRatio(null), "累计额为空按 0 处理");
    }

    @Test
    void thresholdTakesPriorityOverRatioBonus() {
        SplitRule rule = ruleWithThreshold(100_000L, 2000);
        assertEquals(1500, rule.resolveInvestorRatio(0L));
        assertEquals(1500, rule.resolveInvestorRatio(99_999L));
        assertEquals(2000, rule.resolveInvestorRatio(100_000L));
        assertEquals(2000, rule.resolveInvestorRatio(100_001L));
    }

    @Test
    void afterThresholdInvestorGetsHigherShare() {
        SplitRule rule = ruleWithThreshold(100_000L, 2000);
        // 实付 2000，2 件有渠道：门店 600，资源方 200，base=1200
        // 达标后投资人 = 1200 * 2000/10000 = 240
        SplitCalculator.SplitAmount amount =
                calculator.calc(2000L, 2, true, rule, 0L, List.of(), 100_000L);
        assertEquals(240L, amount.investor());
        // 平台尾差 = 2000 - 600 - 200 - 240 = 960
        assertEquals(960L, amount.platform());
    }

    @Test
    void afterThresholdStillSumsToPaidAmount() {
        SplitRule rule = ruleWithThreshold(100_000L, 2000);
        SplitCalculator.SplitAmount amount =
                calculator.calc(2000L, 2, true, rule, 0L, List.of(), 100_000L);
        long sum = amount.platform() + amount.store() + amount.channel()
                + amount.investor() + amount.supplier();
        assertEquals(2000L, sum, "达标后五方金额之和仍须等于实付金额");
    }
}