package com.wuling.finance.yeepay;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link YeepayConfigReader} 的回归测试。
 *
 * <p><b>背景</b>：{@code DivideRefundService} 原先用
 * {@code @Value("${app.pay.yeepay.platform-merchant-no:}")} 读平台商商编，
 * 但该配置实际存在 {@code platform_profile.pay_config}（由 trade-service
 * 通过内部接口拉取）。server 进程读环境变量永远是空串，
 * 导致退款归集明细的 {@code ledgerNo} 为空、被易宝拒绝。
 *
 * <p>本测试锁住：配置必须从数据库读出来，且缺字段时返回 null
 * 而不是空串（空串会被当成合法值发给易宝）。
 */
class YeepayConfigReaderTest {

    private static final String FULL_CONFIG = """
            {"mchId":"1900000000",
             "yeepayAppKey":"app_10094363805",
             "yeepayMerchantNo":"10094363805",
             "yeepayPlatformMerchantNo":"10094363805",
             "yeepayPrivateKey":"v1:encrypted",
             "yeepayYopPublicKey":"public"}
            """;

    private YeepayConfigReader newReader(String payConfigJson) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(payConfigJson == null
                        ? List.of()
                        : List.of(Map.of("pay_config", payConfigJson)));
        return new YeepayConfigReader(jdbc, new ObjectMapper());
    }

    @Test
    @DisplayName("平台商商编从 pay_config 读出（修复 @Value 读不到的问题）")
    void readsPlatformMerchantNoFromDb() {
        YeepayConfigReader reader = newReader(FULL_CONFIG);

        assertEquals("10094363805", reader.platformMerchantNo(),
                "平台商商编必须能从数据库配置读出，否则退款归集 ledgerNo 为空");
    }

    @Test
    @DisplayName("服务商商编与 appKey 也能读出")
    void readsMerchantNoAndAppKey() {
        YeepayConfigReader reader = newReader(FULL_CONFIG);

        assertEquals("10094363805", reader.merchantNo());
        assertEquals("app_10094363805", reader.appKey());
        assertTrue(reader.isConfigured(), "appKey 与商编齐全时应视为已配置");
    }

    @Test
    @DisplayName("缺 platformMerchantNo 时返回 null，而不是空串")
    void missingPlatformMerchantNoReturnsNull() {
        YeepayConfigReader reader = newReader(
                "{\"yeepayAppKey\":\"app_x\",\"yeepayMerchantNo\":\"100\"}");

        assertNull(reader.platformMerchantNo(),
                "缺字段必须返回 null：空串会被当成合法 ledgerNo 发给易宝");
    }

    @Test
    @DisplayName("平台档案不存在时安全返回 null，不抛异常")
    void missingProfileReturnsNull() {
        YeepayConfigReader reader = newReader(null);

        assertNull(reader.platformMerchantNo());
        assertNull(reader.merchantNo());
        assertFalse(reader.isConfigured(), "无档案时不应视为已配置");
    }

    @Test
    @DisplayName("非法 JSON 不抛异常，返回 null 并降级")
    void invalidJsonDegradesGracefully() {
        YeepayConfigReader reader = newReader("not-a-json");

        assertNull(reader.platformMerchantNo(), "解析失败应降级为 null");
        assertFalse(reader.isConfigured());
    }

    @Test
    @DisplayName("结果有缓存，重复读取不重复查库")
    void cachesRepeatedReads() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("pay_config", FULL_CONFIG)));
        YeepayConfigReader reader = new YeepayConfigReader(jdbc, new ObjectMapper());

        reader.platformMerchantNo();
        reader.platformMerchantNo();
        reader.merchantNo();

        // 三次调用只应查库一次（60s TTL 内）
        verify(jdbc, times(1)).queryForList(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("invalidate 后重新查库（后台改配置可立即生效）")
    void invalidateForcesReload() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("pay_config", FULL_CONFIG)));
        YeepayConfigReader reader = new YeepayConfigReader(jdbc, new ObjectMapper());

        reader.platformMerchantNo();
        reader.invalidate();
        reader.platformMerchantNo();

        verify(jdbc, times(2)).queryForList(anyString(), any(Object[].class));
    }
}
