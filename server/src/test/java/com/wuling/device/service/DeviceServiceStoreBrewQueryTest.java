package com.wuling.device.service;

import com.wuling.common.api.PageResult;
import com.wuling.common.audit.AuditLogService;
import com.wuling.device.mapper.DeviceBrewRecordMapper;
import com.wuling.device.mapper.DeviceInfoMapper;
import com.wuling.device.mapper.DeviceMessageErrorMapper;
import com.wuling.device.mapper.DeviceStatusLogMapper;
import com.wuling.device.mapper.DeviceStoreBindingMapper;
import com.wuling.subject.mapper.BizSubjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 门店侧冲泡记录查询测试（mock JdbcTemplate，不连库）。
 *
 * <p>锁定这次新增的搜索能力里最容易踩错的三件事：
 * <ol>
 *   <li><b>统计与列表 SQL 必须都带 device_info 的 join</b>：别名条件引用 {@code d.alias}，
 *       统计语句缺 join 会直接 SQL 报错；</li>
 *   <li><b>通配符必须转义</b>：用户敲一个 {@code %} 不能变成「全表匹配」；</li>
 *   <li>空关键词不产生任何额外条件，关键词与时间/设备条件可叠加。</li>
 * </ol>
 */
class DeviceServiceStoreBrewQueryTest {

    private JdbcTemplate jdbcTemplate;
    private DeviceService deviceService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(7L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        deviceService = new DeviceService(
                mock(DeviceInfoMapper.class),
                mock(DeviceStoreBindingMapper.class),
                mock(DeviceBrewRecordMapper.class),
                mock(DeviceStatusLogMapper.class),
                mock(DeviceMessageErrorMapper.class),
                mock(BizSubjectMapper.class),
                jdbcTemplate,
                mock(AuditLogService.class));
    }

    @Test
    void keywordSearchesAllFieldsAndJoinsDeviceTableInBothQueries() {
        PageResult<Map<String, Object>> page = deviceService.pageStoreBrewRecords(
                1, 20, 102L, null, "欧米伽", null, null);

        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> listSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> countArgs = ArgumentCaptor.forClass(Object[].class);
        ArgumentCaptor<Object[]> listArgs = ArgumentCaptor.forClass(Object[].class);
        org.mockito.Mockito.verify(jdbcTemplate).queryForObject(countSql.capture(), eq(Long.class), countArgs.capture());
        org.mockito.Mockito.verify(jdbcTemplate).query(listSql.capture(), any(RowMapper.class), listArgs.capture());

        assertEquals(7L, page.getTotal());
        // 统计与列表都必须 join 设备表：别名是关键词的匹配字段之一
        assertTrue(countSql.getValue().contains("left join device_info"),
                "统计 SQL 必须 join device_info（别名条件引用了 d.alias）");
        assertTrue(listSql.getValue().contains("left join device_info"));
        for (String sql : List.of(countSql.getValue(), listSql.getValue())) {
            assertTrue(sql.contains("b.tea_name like ?"), "应搜茶品名");
            assertTrue(sql.contains("b.tea_code like ?"), "应搜茶品编码");
            assertTrue(sql.contains("b.device_sn like ?"), "应搜设备编码");
            assertTrue(sql.contains("b.device_name like ?"), "应搜设备ID");
            assertTrue(sql.contains("coalesce(d.alias, '') like ?"), "应搜设备别名且对 NULL 安全");
            assertTrue(sql.contains("b.customer_code like ?"), "应搜客户代码");
            assertTrue(sql.contains("b.serial_number like ?"), "应搜序列号");
            assertTrue(sql.contains("escape '\\\\'"), "必须显式指定转义符");
        }
        // 门店过滤 + 7 个关键词参数（列表再多 size/offset）
        assertEquals(8, countArgs.getValue().length);
        assertEquals(10, listArgs.getValue().length);
        assertEquals("%欧米伽%", countArgs.getValue()[1]);
        assertEquals(102L, countArgs.getValue()[0]);
    }

    @Test
    void keywordWildcardsAreEscaped() {
        deviceService.pageStoreBrewRecords(1, 20, 102L, null, "100%_a\\b", null, null);

        ArgumentCaptor<Object[]> countArgs = ArgumentCaptor.forClass(Object[].class);
        org.mockito.Mockito.verify(jdbcTemplate)
                .queryForObject(anyString(), eq(Long.class), countArgs.capture());

        // % 、_ 与反斜杠都被转义，避免「敲一个 % 等于全表匹配」
        assertEquals("%100\\%\\_a\\\\b%", countArgs.getValue()[1]);
    }

    @Test
    void keywordIsTrimmedAndTruncated() {
        String longKeyword = "x".repeat(80);
        deviceService.pageStoreBrewRecords(1, 20, 102L, null, "  " + longKeyword + "  ", null, null);

        ArgumentCaptor<Object[]> countArgs = ArgumentCaptor.forClass(Object[].class);
        org.mockito.Mockito.verify(jdbcTemplate)
                .queryForObject(anyString(), eq(Long.class), countArgs.capture());

        String expected = "%" + "x".repeat(DeviceService.KEYWORD_MAX_LENGTH) + "%";
        assertEquals(expected, countArgs.getValue()[1]);
    }

    @Test
    void blankKeywordAddsNoCondition() {
        deviceService.pageStoreBrewRecords(1, 20, 102L, null, "   ", null, null);

        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> countArgs = ArgumentCaptor.forClass(Object[].class);
        org.mockito.Mockito.verify(jdbcTemplate).queryForObject(countSql.capture(), eq(Long.class), countArgs.capture());

        assertFalse(countSql.getValue().contains("like ?"), "空关键词不应产生模糊条件");
        assertEquals(1, countArgs.getValue().length);
    }

    @Test
    void keywordCombinesWithDeviceAndTimeRange() {
        deviceService.pageStoreBrewRecords(1, 20, 102L, "ZPDS2512090086G", "209",
                LocalDateTime.of(2026, 10, 1, 0, 0), LocalDateTime.of(2026, 10, 8, 0, 0));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        org.mockito.Mockito.verify(jdbcTemplate).queryForObject(sql.capture(), eq(Long.class), args.capture());

        assertTrue(sql.getValue().contains("b.device_sn = ?"));
        assertTrue(sql.getValue().contains("b.base_create_time >= ?"));
        assertTrue(sql.getValue().contains("b.base_create_time < ?"));
        // 门店 + 设备 + 关键词×7 + 起止时间
        assertEquals(11, args.getValue().length);
    }
}
