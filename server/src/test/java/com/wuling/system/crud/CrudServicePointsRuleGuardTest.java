package com.wuling.system.crud;

import com.wuling.common.cache.AppConfigCacheService;
import com.wuling.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 积分获取规则写入守卫测试。
 *
 * <p><b>背景</b>：reward 原先是一段文本（如 '+3币/人'），数字埋在字符串里，
 * 无法用于「按规则发放时光币」。V58 把奖励拆成结构化数字后，
 * 必须由服务端保证：类型合法、数值为正、per-yuan 必须带基准金额。
 */
class CrudServicePointsRuleGuardTest {

    private JdbcTemplate jdbcTemplate;
    private CrudService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new CrudService(jdbcTemplate, mock(AppConfigCacheService.class));
        lenient().when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        lenient().when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(new LinkedHashMap<>(Map.of("id", 1))));
    }

    private Map<String, Object> payload(Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", "consume");
        m.put("action", "每消费1元");
        m.putAll(extra);
        return m;
    }

    @Test
    @DisplayName("per-yuan 必须带基准金额与正数值")
    void perYuanRequiresBasisAmount() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update("pointsEarningRules", 1,
                        payload(Map.of("reward_type", "per-yuan", "reward_value", 1))));
        assertTrue(ex.getMessage().contains("基准") || ex.getMessage().contains("金额"),
                "per-yuan 缺少 basis_amount 必须被拒绝");

        assertDoesNotThrow(() -> service.update("pointsEarningRules", 1,
                payload(Map.of("reward_type", "per-yuan", "reward_value", 1, "basis_amount", 100))));
    }

    @Test
    @DisplayName("reward_type 必须是受支持的枚举")
    void rewardTypeMustBeSupported() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update("pointsEarningRules", 1,
                        payload(Map.of("reward_type", "unknown-type", "reward_value", 1))));
        assertTrue(ex.getMessage().contains("奖励类型"), "非法 reward_type 必须被拒绝");
    }

    @Test
    @DisplayName("reward_value 必须为正整数")
    void rewardValueMustBePositive() {
        assertThrows(BusinessException.class,
                () -> service.update("pointsEarningRules", 1,
                        payload(Map.of("reward_type", "fixed", "reward_value", 0))));
        assertThrows(BusinessException.class,
                () -> service.update("pointsEarningRules", 1,
                        payload(Map.of("reward_type", "fixed", "reward_value", -3))));
    }

    @Test
    @DisplayName("daily_limit 允许为空，但不能为负")
    void dailyLimitNullableButNotNegative() {
        assertDoesNotThrow(() -> service.update("pointsEarningRules", 1,
                payload(Map.of("reward_type", "fixed", "reward_value", 3, "daily_limit", 2))));
        assertThrows(BusinessException.class,
                () -> service.update("pointsEarningRules", 1,
                        payload(Map.of("reward_type", "fixed", "reward_value", 3, "daily_limit", -1))));
    }

    @Test
    @DisplayName("multiplier 类型不允许带基准金额")
    void multiplierMustNotCarryBasisAmount() {
        assertDoesNotThrow(() -> service.update("pointsEarningRules", 1,
                payload(Map.of("reward_type", "multiplier", "reward_value", 2))));
        assertThrows(BusinessException.class,
                () -> service.update("pointsEarningRules", 1,
                        payload(Map.of("reward_type", "multiplier", "reward_value", 2, "basis_amount", 100))));
    }
}
