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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CrudServiceMiniTabBarGuardTest {

    private static final String VALID_VALUE =
            "{\"schemaVersion\":2,\"defaultThirdTab\":\"memberZone\","
                    + "\"releases\":{\"1.3.5\":\"memberZone\",\"1.3.6\":\"tongueHealth\"}}";

    private AppConfigCacheService cacheService;
    private CrudService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        cacheService = mock(AppConfigCacheService.class);
        service = new CrudService(jdbcTemplate, cacheService);
        lenient().when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        lenient().when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(new LinkedHashMap<>(Map.of(
                        "id", 9,
                        "config_key", "mini_tab_bar",
                        "config_name", "小程序底部导航",
                        "value", VALID_VALUE,
                        "status", "enabled"
                ))));
    }

    private Map<String, Object> payload(String value) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("configKey", "mini_tab_bar");
        data.put("configName", "小程序底部导航");
        data.put("value", value);
        data.put("sort", 9);
        data.put("status", "enabled");
        return data;
    }

    @Test
    @DisplayName("mini_tab_bar 必须拒绝非 JSON 配置")
    void rejectsMalformedJson() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create("appConfig", payload("{bad json")));
        assertTrue(error.getMessage().contains("JSON"), "非法 JSON 必须给出明确错误");
    }

    @Test
    @DisplayName("schemaVersion 必须为 2")
    void rejectsOldSchemaVersion() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create("appConfig",
                        payload("{\"schemaVersion\":1,\"defaultThirdTab\":\"memberZone\",\"releases\":{}}")));
        assertTrue(error.getMessage().contains("schemaVersion"), "旧 schema 必须由迁移转换，写入时必须拒绝");
    }

    @Test
    @DisplayName("defaultThirdTab 与版本映射值只允许两个受支持值")
    void rejectsUnknownThirdTab() {
        BusinessException invalidDefault = assertThrows(BusinessException.class,
                () -> service.create("appConfig",
                        payload("{\"schemaVersion\":2,\"defaultThirdTab\":\"unknown\",\"releases\":{}}")));
        assertTrue(invalidDefault.getMessage().contains("defaultThirdTab"), "未知默认项必须被拒绝");

        BusinessException invalidRelease = assertThrows(BusinessException.class,
                () -> service.create("appConfig",
                        payload("{\"schemaVersion\":2,\"defaultThirdTab\":\"memberZone\","
                                + "\"releases\":{\"1.3.5\":\"unknown\"}}")));
        assertTrue(invalidRelease.getMessage().contains("releases"), "未知版本映射必须被拒绝");
    }

    @Test
    @DisplayName("releases 版本号只允许字母数字和小数点")
    void rejectsInvalidVersionKey() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create("appConfig",
                        payload("{\"schemaVersion\":2,\"defaultThirdTab\":\"memberZone\","
                                + "\"releases\":{\"1.3/5\":\"memberZone\"}}")));
        assertTrue(error.getMessage().contains("版本号"), "非法版本号必须被拒绝");
    }

    @Test
    @DisplayName("保存更新时同样执行版本配置校验")
    void rejectsInvalidConfigOnUpdate() {
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("value", "{\"schemaVersion\":2,\"defaultThirdTab\":\"unknown\",\"releases\":{}}");
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.update("appConfig", 9L, update));
        assertTrue(error.getMessage().contains("defaultThirdTab"), "更新时未知默认项必须被拒绝");
    }

    @Test
    @DisplayName("mini_tab_bar 不得携带白名单以外字段")
    void rejectsUnknownField() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create("appConfig",
                        payload("{\"schemaVersion\":2,\"defaultThirdTab\":\"memberZone\","
                                + "\"releases\":{},\"pagePath\":\"/pages/home/home\"}")));
        assertTrue(error.getMessage().contains("仅支持"), "未知字段必须被拒绝");
    }

    @Test
    @DisplayName("合法版本映射保存后会失效对应配置缓存")
    void validConfigEvictsCache() {
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("configKey", "mini_tab_bar");
        update.put("value", VALID_VALUE);
        assertDoesNotThrow(() -> service.update("appConfig", 9L, update));
        verify(cacheService).evict("mini_tab_bar");
    }
}
