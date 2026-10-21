package com.wuling.device.controller;

import com.wuling.auth.service.AdminRbacGuard;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.device.dto.DeviceImportResult;
import com.wuling.device.dto.DeviceUpsert;
import com.wuling.device.mns.MnsQueueStatusProvider;
import com.wuling.device.service.DeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 后台设备接口测试（只覆盖本次新增的队列自检入口）。
 *
 * <p>重点：{@code refresh} 参数必须只在显式传 1 时才强制实时查询 ——
 * 否则每次打开「设备管理」页都会打一次公网，页面反而变慢。
 */
class AdminDeviceControllerTest {

    private MnsQueueStatusProvider queueStatusProvider;
    private DeviceService deviceService;
    private AdminRbacGuard rbacGuard;
    private AdminDeviceController controller;

    @BeforeEach
    void setUp() {
        queueStatusProvider = mock(MnsQueueStatusProvider.class);
        deviceService = mock(DeviceService.class);
        rbacGuard = mock(AdminRbacGuard.class);
        controller = new AdminDeviceController(deviceService, queueStatusProvider, rbacGuard);
    }

    @Test
    void importDelegatesWholeBatchToService() {
        List<DeviceUpsert> rows = List.of(new DeviceUpsert());
        DeviceImportResult expected = DeviceImportResult.imported(1);
        when(deviceService.importDevices(rows)).thenReturn(expected);

        assertEquals(expected, controller.importDevices(rows).getData());
        inOrder(rbacGuard, deviceService).verify(rbacGuard).assertMenuAccess("subject_device", "导入设备");
        verify(deviceService).importDevices(rows);
    }

    @Test
    void importStopsWhenDeviceMenuPermissionIsDenied() {
        List<DeviceUpsert> rows = List.of(new DeviceUpsert());
        doThrow(new BusinessException(ResultCode.FORBIDDEN, "无权限"))
                .when(rbacGuard).assertMenuAccess("subject_device", "导入设备");

        assertThrows(BusinessException.class, () -> controller.importDevices(rows));

        verify(deviceService, never()).importDevices(rows);
        verifyNoInteractions(queueStatusProvider);
    }

    @Test
    void queueStatusUsesCacheByDefault() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("ok", true);
        snapshot.put("cached", true);
        when(queueStatusProvider.snapshot(false)).thenReturn(snapshot);

        assertEquals(snapshot, controller.queueStatus(null).getData());
        verify(queueStatusProvider).snapshot(false);
    }

    @Test
    void queueStatusRefreshesOnlyWhenAsked() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        when(queueStatusProvider.snapshot(true)).thenReturn(snapshot);
        when(queueStatusProvider.snapshot(false)).thenReturn(snapshot);

        controller.queueStatus(1);
        verify(queueStatusProvider).snapshot(true);

        controller.queueStatus(0);
        verify(queueStatusProvider).snapshot(false);
    }
}
