package com.wuling.marketing.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 优惠券「过期未用」归档扫描任务测试。
 *
 * <p>本任务是<b>兜底</b>：「已过期」Tab 的归类由 {@code CouponService#myCoupons} 现算
 * （deriveTab）保证永远正确，任务只负责把过期券落库成 EXPIRED 以便统计与对账。
 * 因此这里只守三条：开关生效 / 按批大小调用 / 单轮异常不抛出。
 */
class CouponExpireSweepJobTest {

    @Test
    @DisplayName("开启时按 batchSize 调用归档")
    void sweepsWhenEnabled() {
        CouponService service = mock(CouponService.class);
        when(service.archiveOverdueUnused(500)).thenReturn(3);

        new CouponExpireSweepJob(service, true, 500).sweep();

        verify(service).archiveOverdueUnused(500);
    }

    @Test
    @DisplayName("关闭时完全不调用归档")
    void skipsWhenDisabled() {
        CouponService service = mock(CouponService.class);

        new CouponExpireSweepJob(service, false, 500).sweep();

        verify(service, never()).archiveOverdueUnused(anyInt());
    }

    @Test
    @DisplayName("单轮异常不得抛出，避免调度被中断")
    void swallowsFailure() {
        CouponService service = mock(CouponService.class);
        doThrow(new IllegalStateException("db down")).when(service).archiveOverdueUnused(500);

        assertDoesNotThrow(() -> new CouponExpireSweepJob(service, true, 500).sweep());
    }
}