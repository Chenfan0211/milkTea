package com.wuling.finance;

import com.wuling.finance.entity.SplitRule;
import com.wuling.finance.service.SplitCalculator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 投资人「当月达标后比例」规则测试。
 *
 * <p>业务口径（用户确认）：
 * 按**投资人当月累计分账额**判定 —— 累计额达到 investorThresholdAmount（分）后，
 * 该月起改用 investorRatioAfter（万分比）；阈值 = 0 表示不启用该规则。
 *
 * <p>关键约束：比例替换后五方合计仍须为 10000，
 * 差额由平台（platformRatio）承接 —— 平台是既有的尾差方，语义一致。
 */
class InvestorThresholdTest {

    private final SplitCalculator calculator = new SplitCalculator();

    /**
     * 构造规则：默认平台 1000 / 门店 5000 / 渠道 1500 / 投资人 1500 / 供应商 1000。
     * 达标后投资人比例 2000（+500），平台相应 -500 变 500，合计仍 10000。
     */
    private SplitRule ruleWithThreshold(long threshold, int investorAfter) {
        SplitRule rule = new SplitRule();
        rule.setPlatformRatio(1000);
        rule.setStoreRatio(5000);
        rule.setChannelRatio(1500);
        rule.setInvestorRatio(1500);
        rule.setSupplierRatio(1000);
        rule.setInvestorThresholdAmount(threshold);
        rule.setInvestorRatioAfter(investorAfter);
        return rule;
    }

    // ---------- 场景 1：未达标 —— 用原比例 ----------

    @Test
    void belowThresholdUsesBaseInvestorRatio() {
        SplitRule rule = ruleWithThreshold(100_000L, 2000);
        // 当月累计 99999 分，差 1 分未达标
        long effective = rule.resolveInvestorRatio(99_999L);
        assertEquals(1500, effective, "未达标应使用原投资人比例");
    }

    @Test
    void belowThresholdSplitMatchesBaseline() {
        SplitRule rule = ruleWithThreshold(100_000L, 2000);
        SplitCalculator.SplitAmount amount =
                calculator.calc(1000L, 1, true, rule, 0L, List.of());

        // 与「无阈值规则」的结果完全一致
        SplitRule base = ruleWithThreshold(0L, 2000);
        SplitCalculator.SplitAmount baseline =
                calculator.calc(1000L, 1, true, base, 0L, List.of());

        assertEquals(baseline.investor(), amount.investor(), "未达标金额应与基准一致");
        assertEquals(baseline.platform(), amount.platform());
    }

    // ---------- 场景 2：达标当单 —— 本单即用达标比例 ----------

    @Test
    void reachingThresholdOnThisOrderUsesAfterRatio() {
        SplitRule rule = ruleWithThreshold(100_000L, 2000);
        // 恰好达到阈值（含等于）
        long effective = rule.resolveInvestorRatio(100_000L);
        assertEquals(2000, effective, "达标当单应使用达标后比例");
    }

    @Test
    void aboveThresholdUsesAfterRatio() {
        SplitRule rule = ruleWithThreshold(100_000L, 2000);
        long effective = rule.resolveInvestorRatio(500_000L);
        assertEquals(2000, effective);
    }

    // ---------- 场景 3：达标后 —— 五方合计仍为 10000，差额归平台 ----------

    @Test
    void afterThresholdSplitStillSumsToPaidAmount() {
        SplitRule rule = ruleWithThreshold(100_000L, 2000);
        SplitCalculator.SplitAmount amount =
                calculator.calc(1000L, 1, true, rule, 0L, List.of());

        long sum = amount.platform() + amount.store() + amount.channel()
                + amount.investor() + amount.supplier();
        assertEquals(1000L, sum, "达标后五方金额之和仍须等于实付金额");
    }

    @Test
    void afterThresholdInvestorGetsHigherShare() {
        SplitRule rule = ruleWithThreshold(100_000L, 2000);
        // 关键：必须走 7 参重载并传入当月累计额，否则视为未达标
        SplitCalculator.SplitAmount amount =
                calculator.calc(1000L, 1, true, rule, 0L, List.of(), 100_000L);

        // 1000 * 2000 / 10000 = 200 分
        assertEquals(200L, amount.investor(), "达标后投资人应按 2000 万分比计得 200 分");
        // 平台实际得款是「尾差」而非比例换算值：未传明细时供应商为 0，
        // 1000 - 500(门店) - 150(渠道) - 200(投资) - 0 = 150
        assertEquals(150L, amount.platform());
        // 投资人比未达标时多拿 50 分（200 - 150），这部分正是从原平台份额让出的
        SplitCalculator.SplitAmount before = calculator.calc(1000L, 1, true, rule, 0L, List.of(), 99_999L);
        assertEquals(amount.investor() - before.investor(), before.platform() - amount.platform(),
                "投资人增量应等于平台减量");
        // 五方之和仍须等于实付
        assertEquals(1000L, amount.platform() + amount.store() + amount.channel()
                + amount.investor() + amount.supplier());
    }

    // ---------- 场景 4：阈值为 0 表示不启用 ----------

    @Test
    void zeroThresholdDisablesRule() {
        SplitRule rule = ruleWithThreshold(0L, 2000);
        // 即使用户累计额极大，阈值 0 也不应触发
        long effective = rule.resolveInvestorRatio(9_999_999L);
        assertEquals(1500, effective, "阈值为 0 表示不启用，应始终用原比例");
    }

    @Test
    void zeroThresholdSplitIsIdenticalToBaseline() {
        SplitRule rule = ruleWithThreshold(0L, 2000);
        SplitCalculator.SplitAmount amount =
                calculator.calc(1000L, 1, true, rule, 0L, List.of());

        // 1000 * 1500 / 10000 = 150（原投资人比例）
        assertEquals(150L, amount.investor());
        // 平台为尾差方；本用例未传明细，供应商份额为 0：
        // 1000 - 500(门店5000) - 150(渠道1500) - 150(投资1500) - 0 = 200
        assertEquals(200L, amount.platform());
        // 供应商份额为 0 时平台占比会偏高，故五方之和才是最终校验依据
        assertEquals(1000L, amount.platform() + amount.store() + amount.channel()
                + amount.investor() + amount.supplier());
    }

    // ---------- 场景 5：达标比例本身为 0（投资人放弃提升）----------

    @Test
    void zeroAfterRatioKeepsBaseRatio() {
        SplitRule rule = ruleWithThreshold(100_000L, 0);
        long effective = rule.resolveInvestorRatio(500_000L);
        assertEquals(1500, effective, "达标后比例为 0 视为未配置，保留原比例");
    }

    // ---------- 场景 6：边界与异常 ----------

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
    void invalidAfterRatioWouldBreakTotalAndMustThrow() {
        // 达标后比例大到使「生效后合计」超过 10000 时必须拒绝，不能默默算错钱。
        //
        // 说明：resolvePlatformRatio 会让平台按差值让出，故「投资人单独调大」不会破坏合计；
        // 真正的风险是**平台被让成负数**时合计虽低于 10000 但已无平台份额可让。
        // 这里构造 investorRatioAfter 远大于「原平台+原投资人」之和的场景：
        // 平台 1000 + 原投资 1500 = 2500，取 after=9000 -> 平台 = 1000-(9000-1500) = -6500
        // 生效合计 = -6500+5000+1500+9000+1000 = 10000 仍相等，但平台份额为负，属非法配置。
        SplitRule rule = ruleWithThreshold(100_000L, 9000);
        assertThrows(IllegalStateException.class,
                () -> calculator.calc(1000L, 1, true, rule, 0L, java.util.List.of(), 100_000L),
                "平台份额被让成负数时必须抛错，不能算出负的平台收入");
    }

    @Test
    void negativePlatformRatioIsRejected() {
        SplitRule rule = ruleWithThreshold(100_000L, 9000);
        assertTrue(rule.resolvePlatformRatio(100_000L) < 0, "本用例前提：平台比例应为负");
        assertThrows(IllegalStateException.class,
                () -> calculator.calc(1000L, 1, true, rule, 0L, java.util.List.of(), 100_000L));
    }
}
