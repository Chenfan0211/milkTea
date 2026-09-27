package com.wuling.marketing.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.wuling.marketing.entity.PointsEarningRule;
import com.wuling.user.mapper.AppUserMapper;
import com.wuling.marketing.mapper.CouponMapper;
import com.wuling.marketing.mapper.ExchangeOrderMapper;
import com.wuling.marketing.mapper.PointsCategoryMapper;
import com.wuling.marketing.mapper.PointsEarningRuleMapper;
import com.wuling.marketing.mapper.PointsProductMapper;
import com.wuling.marketing.mapper.PointsRecordMapper;
import com.wuling.marketing.mapper.PointsSigninMapper;
import com.wuling.marketing.mapper.PointsSigninRuleMapper;
import com.wuling.marketing.mapper.UserCouponMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PointsEarningRuleEnabledTest {

    private PointsEarningRuleMapper ruleMapper;
    private PointsService service;

    @BeforeEach
    void setUp() {
        ruleMapper = mock(PointsEarningRuleMapper.class);
        service = new PointsService(
                mock(PointsProductMapper.class),
                mock(PointsRecordMapper.class),
                mock(PointsSigninMapper.class),
                mock(PointsSigninRuleMapper.class),
                ruleMapper,
                mock(AppUserMapper.class),
                mock(ExchangeOrderMapper.class),
                mock(PointsCategoryMapper.class),
                mock(CouponMapper.class),
                mock(UserCouponMapper.class));
    }

    @Test
    @DisplayName("App 端只返回启用规则（enabledEarningRules 走带条件的查询）")
    void enabledRulesFiltersDisabled() {
        // enabledEarningRules 内部用 LambdaQueryWrapper.eq(enabled,1) 过滤；
        // mock 的 selectList 不执行条件，但能证明该方法被调用且返回 mapper 结果。
        PointsEarningRule enabled = new PointsEarningRule();
        enabled.setCode("consume");
        enabled.setEnabled(1);

        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(enabled));

        List<PointsEarningRule> result = service.enabledEarningRules();

        assertEquals(1, result.size(), "应返回 mapper 查询结果");
        assertEquals("consume", result.get(0).getCode());
        verify(ruleMapper).selectList(any(Wrapper.class));
    }

    @Test
    @DisplayName("实体必须包含 enabled 字段")
    void entityExposesEnabled() {
        PointsEarningRule rule = new PointsEarningRule();
        rule.setEnabled(1);
        assertEquals(1, rule.getEnabled(), "实体必须支持 enabled 字段");
    }
}
