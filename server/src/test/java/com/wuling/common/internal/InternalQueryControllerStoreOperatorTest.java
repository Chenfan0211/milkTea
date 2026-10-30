package com.wuling.common.internal;

import com.wuling.finance.mapper.SettlementRecordMapper;
import com.wuling.subject.entity.BizSubject;
import com.wuling.subject.mapper.BizSubjectMapper;
import com.wuling.subject.service.StoreOperatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 内部接口 store-operator-check 的回归测试。
 *
 * <p><b>为什么需要它</b>：该判定的实现从控制器搬到了
 * {@link StoreOperatorService}（内部接口与 app 侧接口共用），
 * 属于「纯搬迁」类改动 —— 这类改动最容易悄悄改掉返回结构或参数顺序，
 * 而调用方（trade-service 核销、product-service 门店选品）拿到的却是 403。
 * 因此固定住返回结构与传参。
 */
class InternalQueryControllerStoreOperatorTest {

    private StoreOperatorService storeOperatorService;
    private BizSubjectMapper bizSubjectMapper;
    private JdbcTemplate jdbcTemplate;
    private InternalQueryController controller;

    @BeforeEach
    void setUp() {
        storeOperatorService = mock(StoreOperatorService.class);
        bizSubjectMapper = mock(BizSubjectMapper.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        controller = new InternalQueryController(bizSubjectMapper,
                mock(SettlementRecordMapper.class), jdbcTemplate, storeOperatorService);
    }

    @Test
    void allowedTrueWhenOperatorServiceAllows() {
        when(storeOperatorService.canOperate(7L, 101L)).thenReturn(true);

        Map<String, Object> result = controller.storeOperatorCheck(7L, 101L);

        assertEquals(Map.of("allowed", true), result);
        verify(storeOperatorService).canOperate(7L, 101L);
    }

    @Test
    void allowedFalseWhenOperatorServiceDenies() {
        when(storeOperatorService.canOperate(any(), any())).thenReturn(false);

        assertEquals(Map.of("allowed", false), controller.storeOperatorCheck(7L, 101L));
    }

    @Test
    void missingParamsArePassedThroughAndDenied() {
        // 未 mock 的返回值默认 false：参数缺失时必须是「拒绝」而不是放行或 NPE
        assertEquals(Map.of("allowed", false), controller.storeOperatorCheck(null, 101L));
    }

    @Test
    void subjectNameIncludesStoreBusinessStatus() {
        BizSubject subject = new BizSubject();
        subject.setId(101L);
        subject.setName("停用店");
        when(bizSubjectMapper.selectById(101L)).thenReturn(subject);
        when(jdbcTemplate.query(contains("business_status"), any(RowMapper.class), eq(101L)))
                .thenReturn(List.of("closed"));

        Map<String, Object> result = controller.subjectName(101L);

        assertEquals("停用店", result.get("name"));
        assertEquals("closed", result.get("businessStatus"));
    }
}
