package com.wuling.finance;

import com.wuling.finance.dto.InvestorCommissionView;
import com.wuling.finance.entity.SplitRule;
import com.wuling.finance.mapper.FundFlowMapper;
import com.wuling.finance.mapper.SettlementRecordMapper;
import com.wuling.finance.mapper.SplitRuleMapper;
import com.wuling.finance.mapper.SubjectAccountMapper;
import com.wuling.finance.port.TradeOrderQueryPort;
import com.wuling.finance.service.RoleWorkbenchService;
import com.wuling.subject.port.SubjectQueryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 投资人「月度分佣」聚合服务测试（只读，无 DB）。
 *
 * <p>锁定三件事：
 * <ol>
 *   <li>达标口径：当月累计分佣 >= 阈值 -> 改用 investorRatioAfter，与 SplitRule 一致；</li>
 *   <li>进度百分比 0-100 封顶、距阈值差额正确；</li>
 *   <li>门店销售额/单数按 store_subject_id 聚合，无门店时返回空。</li>
 * </ol>
 */
class RoleWorkbenchCommissionTest {

    private SettlementRecordMapper settlementRecordMapper;
    private SplitRuleMapper splitRuleMapper;
    private JdbcTemplate jdbcTemplate;
    private SubjectQueryPort subjectQueryPort;
    private RoleWorkbenchService service;

    @BeforeEach
    void setUp() {
        settlementRecordMapper = mock(SettlementRecordMapper.class);
        splitRuleMapper = mock(SplitRuleMapper.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        subjectQueryPort = mock(SubjectQueryPort.class);

        lenient().when(subjectQueryPort.findStoreIdsByInvestor(anyLong())).thenReturn(List.of());
        lenient().when(settlementRecordMapper.selectSumCurrentMonth(anyLong(), any(), any())).thenReturn(0L);
        lenient().when(settlementRecordMapper.selectSumSettleable(anyLong())).thenReturn(0L);
        lenient().when(settlementRecordMapper.selectSumPending(anyLong())).thenReturn(0L);
        lenient().when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        lenient().when(splitRuleMapper.selectOne(any())).thenReturn(null);

        service = new RoleWorkbenchService(
                mock(SubjectAccountMapper.class),
                mock(FundFlowMapper.class),
                settlementRecordMapper,
                splitRuleMapper,
                jdbcTemplate,
                mock(TradeOrderQueryPort.class),
                subjectQueryPort);
    }

    private SplitRule rule(long threshold, int base, int after) {
        SplitRule rule = new SplitRule();
        rule.setInvestorRatio(base);
        rule.setInvestorThresholdAmount(threshold);
        rule.setInvestorRatioAfter(after);
        return rule;
    }

    @Test
    void belowThresholdUsesBaseRatioAndComputesProgress() {
        when(splitRuleMapper.selectOne(any())).thenReturn(rule(100_000L, 1500, 2000));
        when(settlementRecordMapper.selectSumCurrentMonth(eq(7L), any(), any())).thenReturn(68_000L);

        InvestorCommissionView view = service.investorCommission(7L);

        assertTrue(view.getRule().isThresholdEnabled());
        assertFalse(view.getCurrent().isReached());
        assertEquals(1500, view.getCurrent().getEffectiveRatio());
        assertEquals(68, view.getProgress().getPercent());
        assertEquals(32_000L, view.getProgress().getRemainToThreshold());
        assertEquals(68_000L, view.getCurrent().getCommissionAmount());
    }

    @Test
    void reachingThresholdUsesAfterRatioAndCapsPercent() {
        when(splitRuleMapper.selectOne(any())).thenReturn(rule(100_000L, 1500, 2000));
        when(settlementRecordMapper.selectSumCurrentMonth(eq(7L), any(), any())).thenReturn(123_456L);

        InvestorCommissionView view = service.investorCommission(7L);

        assertTrue(view.getCurrent().isReached());
        assertEquals(2000, view.getCurrent().getEffectiveRatio());
        assertEquals(100, view.getProgress().getPercent());
        assertEquals(0L, view.getProgress().getRemainToThreshold());
    }

    @Test
    void thresholdDisabledFallsBackToBaseRatio() {
        when(splitRuleMapper.selectOne(any())).thenReturn(rule(0L, 1500, 2000));
        when(settlementRecordMapper.selectSumCurrentMonth(eq(7L), any(), any())).thenReturn(68_000L);

        InvestorCommissionView view = service.investorCommission(7L);

        assertFalse(view.getRule().isThresholdEnabled());
        assertFalse(view.getCurrent().isReached());
        assertEquals(1500, view.getCurrent().getEffectiveRatio());
        assertEquals(0, view.getProgress().getPercent());
    }

    @Test
    void noStoresReturnsEmptyStatsAndZeroSales() {
        when(splitRuleMapper.selectOne(any())).thenReturn(rule(100_000L, 1500, 2000));
        when(subjectQueryPort.findStoreIdsByInvestor(7L)).thenReturn(List.of());

        InvestorCommissionView view = service.investorCommission(7L);

        assertTrue(view.getStores().isEmpty());
        assertEquals(0L, view.getCurrent().getSalesAmount());
    }

    @Test
    void storeStatsAggregateSalesAndCountPerStore() {
        when(splitRuleMapper.selectOne(any())).thenReturn(rule(100_000L, 1500, 2000));
        when(subjectQueryPort.findStoreIdsByInvestor(7L)).thenReturn(List.of(1L, 2L));
        when(subjectQueryPort.findName(1L)).thenReturn("星沙店");
        when(subjectQueryPort.findName(2L)).thenReturn("松雅湖店");
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(520_000L);
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getLong("store_subject_id")).thenReturn(1L);
            when(rs.getLong("sales")).thenReturn(520_000L);
            when(rs.getLong("cnt")).thenReturn(12L);
            handler.processRow(rs);
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

        InvestorCommissionView view = service.investorCommission(7L);

        assertEquals(2, view.getStores().size());
        assertEquals("星沙店", view.getStores().get(0).getName());
        assertEquals(520_000L, view.getStores().get(0).getSalesAmount());
        assertEquals(12L, view.getStores().get(0).getOrderCount());
        assertEquals(0L, view.getStores().get(1).getSalesAmount());
    }
}
