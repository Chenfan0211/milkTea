package com.wuling.common.internal;

import com.wuling.finance.mapper.SettlementRecordMapper;
import com.wuling.subject.mapper.BizSubjectMapper;
import com.wuling.subject.service.StoreOperatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
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
    private InternalQueryController controller;

    @BeforeEach
    void setUp() {
        storeOperatorService = mock(StoreOperatorService.class);
        controller = new InternalQueryController(mock(BizSubjectMapper.class),
                mock(SettlementRecordMapper.class), mock(JdbcTemplate.class), storeOperatorService);
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
}
