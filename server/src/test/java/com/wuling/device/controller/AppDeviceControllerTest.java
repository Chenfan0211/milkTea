package com.wuling.device.controller;

import com.wuling.common.api.PageResult;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.device.service.DeviceService;
import com.wuling.security.CurrentUser;
import com.wuling.subject.service.StoreOperatorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 小程序门店侧设备/冲泡记录接口测试（不依赖数据库）。
 *
 * <p>锁定三件最容易出事的行为：
 * <ol>
 *   <li><b>越权</b>：未登录 401、非本店经营者 403，且「拒绝时不得查数据」；</li>
 *   <li><b>范围不可放大</b>：查询门店一律取路径参数，设备清单固定 bound 口径；</li>
 *   <li><b>分页与时间边界</b>：size 封顶 50、结束日换算成次日 00:00:00 开区间上界。</li>
 * </ol>
 */
class AppDeviceControllerTest {

    private DeviceService deviceService;
    private StoreOperatorService storeOperatorService;
    private AppDeviceController controller;

    @BeforeEach
    void setUp() {
        deviceService = mock(DeviceService.class);
        storeOperatorService = mock(StoreOperatorService.class);
        controller = new AppDeviceController(deviceService, storeOperatorService);
        CurrentUser.set(7L);
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    // ---------- 设备清单 ----------

    @Test
    void devicesRequireLogin() {
        CurrentUser.clear();

        BusinessException error = assertThrows(BusinessException.class, () -> controller.devices(101L, 1L, 20L));

        assertEquals(ResultCode.UNAUTHORIZED, error.getCode());
        verify(deviceService, never()).pageStoreDevices(anyLong(), anyLong(), anyLong());
    }

    @Test
    void devicesRejectOtherStoreWithoutQuerying() {
        when(storeOperatorService.canOperate(7L, 999L)).thenReturn(false);

        BusinessException error = assertThrows(BusinessException.class, () -> controller.devices(999L, 1L, 20L));

        assertEquals(ResultCode.FORBIDDEN, error.getCode());
        verify(deviceService, never()).pageStoreDevices(anyLong(), anyLong(), anyLong());
    }

    @Test
    void devicesUsePathStoreAndCapSize() {
        when(storeOperatorService.canOperate(7L, 101L)).thenReturn(true);
        when(deviceService.pageStoreDevices(anyLong(), anyLong(), anyLong()))
                .thenReturn(PageResult.of(List.of(), 1L, 20L, 0L));

        controller.devices(101L, 0L, 999L);

        // current<1 归一为 1；size 超过上限按 50 截断；门店取路径参数
        verify(deviceService).pageStoreDevices(1L, AppDeviceController.MAX_PAGE_SIZE, 101L);
    }

    @Test
    void devicesDefaultSizeWhenNotPositive() {
        when(storeOperatorService.canOperate(7L, 101L)).thenReturn(true);
        when(deviceService.pageStoreDevices(anyLong(), anyLong(), anyLong()))
                .thenReturn(PageResult.of(List.of(), 1L, 20L, 0L));

        controller.devices(101L, 2L, -5L);

        verify(deviceService).pageStoreDevices(2L, 20L, 101L);
    }

    @Test
    void deviceSummaryRejectsOtherStoreWithoutQuerying() {
        when(storeOperatorService.canOperate(7L, 999L)).thenReturn(false);

        BusinessException error = assertThrows(BusinessException.class, () -> controller.deviceSummary(999L));

        assertEquals(ResultCode.FORBIDDEN, error.getCode());
        verify(deviceService, never()).storeDeviceSummary(anyLong());
    }

    @Test
    void deviceSummaryScopedToPathStore() {
        when(storeOperatorService.canOperate(7L, 101L)).thenReturn(true);
        when(deviceService.storeDeviceSummary(101L)).thenReturn(Map.of("total", 3L, "online", 2L));

        assertEquals(2L, controller.deviceSummary(101L).getData().get("online"));
        verify(deviceService).storeDeviceSummary(101L);
    }

    // ---------- 冲泡记录 ----------

    @Test
    void brewRecordsRequireLogin() {
        CurrentUser.clear();

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.brewRecords(101L, 1L, 20L, null, null, null, null));

        assertEquals(ResultCode.UNAUTHORIZED, error.getCode());
        verify(deviceService, never())
                .pageStoreBrewRecords(anyLong(), anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void brewRecordsRejectOtherStoreWithoutQuerying() {
        when(storeOperatorService.canOperate(7L, 999L)).thenReturn(false);

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.brewRecords(999L, 1L, 20L, null, null, null, null));

        assertEquals(ResultCode.FORBIDDEN, error.getCode());
        verify(deviceService, never())
                .pageStoreBrewRecords(anyLong(), anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void brewRecordsEndDayIsExclusiveNextDayStart() {
        when(storeOperatorService.canOperate(7L, 101L)).thenReturn(true);
        when(deviceService.pageStoreBrewRecords(anyLong(), anyLong(), anyLong(), anyString(), any(), any(), any()))
                .thenReturn(PageResult.of(List.of(), 1L, 20L, 0L));

        controller.brewRecords(101L, 3L, 500L, "ZPDS2512090086G", null, "2026-10-01", "2026-10-07");

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(deviceService).pageStoreBrewRecords(eq(3L), eq(AppDeviceController.MAX_PAGE_SIZE), eq(101L),
                eq("ZPDS2512090086G"), isNull(), from.capture(), to.capture());
        assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0), from.getValue());
        // 结束日 10-07 必须覆盖到当天 23:59:59，故上界取 10-08 00:00:00
        assertEquals(LocalDateTime.of(2026, 10, 8, 0, 0), to.getValue());
    }

    @Test
    void brewRecordsPassKeywordThrough() {
        when(storeOperatorService.canOperate(7L, 101L)).thenReturn(true);
        when(deviceService.pageStoreBrewRecords(anyLong(), anyLong(), anyLong(), any(), anyString(), any(), any()))
                .thenReturn(PageResult.of(List.of(), 1L, 20L, 0L));

        controller.brewRecords(101L, 1L, 20L, null, "  欧米伽  ", "2026-10-10", "2026-10-16");

        // 关键词原样透传（trim 与长度截断在服务层统一处理，避免两个入口各写一套）
        verify(deviceService).pageStoreBrewRecords(eq(1L), eq(20L), eq(101L), isNull(),
                eq("  欧米伽  "), any(), any());
    }

    @Test
    void brewRecordsWithoutRangePassNullBounds() {
        when(storeOperatorService.canOperate(7L, 101L)).thenReturn(true);
        when(deviceService.pageStoreBrewRecords(anyLong(), anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(PageResult.of(List.of(), 1L, 20L, 0L));

        controller.brewRecords(101L, 1L, 20L, null, null, null, null);

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(deviceService).pageStoreBrewRecords(eq(1L), eq(20L), eq(101L), isNull(),
                isNull(), from.capture(), to.capture());
        assertNull(from.getValue());
        assertNull(to.getValue());
    }

    @Test
    void brewRecordsRejectMalformedDay() {
        when(storeOperatorService.canOperate(7L, 101L)).thenReturn(true);

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.brewRecords(101L, 1L, 20L, null, null, "2026/10/01", null));

        assertEquals(ResultCode.BAD_REQUEST, error.getCode());
        verify(deviceService, never())
                .pageStoreBrewRecords(anyLong(), anyLong(), anyLong(), any(), any(), any(), any());
    }
}
