package com.wuling.device.controller;

import com.wuling.auth.service.AdminRbacGuard;
import com.wuling.common.api.PageResult;
import com.wuling.common.api.Result;
import com.wuling.device.dto.AdminDeviceDTO;
import com.wuling.device.dto.BindRequest;
import com.wuling.device.dto.DeviceImportResult;
import com.wuling.device.dto.DeviceUpsert;
import com.wuling.device.dto.UnbindRequest;
import com.wuling.device.mns.MnsQueueStatusProvider;
import com.wuling.device.service.DeviceService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 后台「设备管理」接口。
 *
 * <p>鉴权沿用 {@code /api/v1/admin/**} 的 JWT 体系（AdminAuthInterceptor），
 * 菜单可见性由前端路由 roles + 授权中心控制。
 *
 * <p>为什么绑定/解绑走专用接口而不是通用 CRUD：
 * 换绑需要「同一事务内写绑定历史 + 更新当前归属」，通用 CRUD 只写单表，
 * 无法保证历史与现状一致。
 */
@RestController
@RequestMapping("/api/v1/admin/device")
public class AdminDeviceController {

    private final DeviceService deviceService;
    private final MnsQueueStatusProvider queueStatusProvider;
    private final AdminRbacGuard rbacGuard;

    public AdminDeviceController(DeviceService deviceService,
                                 MnsQueueStatusProvider queueStatusProvider,
                                 AdminRbacGuard rbacGuard) {
        this.deviceService = deviceService;
        this.queueStatusProvider = queueStatusProvider;
        this.rbacGuard = rbacGuard;
    }

    private void assertDeviceMenu(String action) {
        rbacGuard.assertMenuAccess("subject_device", action);
    }

    /** 设备台账分页（含门店名实时 join、绑定状态筛选）。 */
    @GetMapping("/page")
    public Result<PageResult<AdminDeviceDTO>> page(@RequestParam(defaultValue = "1") long current,
                                                  @RequestParam(defaultValue = "10") long size,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) Long storeSubjectId,
                                                  @RequestParam(required = false) String bindStatus,
                                                  @RequestParam(required = false) String onlineStatus,
                                                  @RequestParam(required = false) Integer enabled) {
        assertDeviceMenu("查看设备");
        return Result.ok(deviceService.pageDevices(current, size, keyword, storeSubjectId,
                bindStatus, onlineStatus, enabled));
    }

    /** 新增设备（手工建档，设备后续上报会自动落到该台账）。 */
    @PostMapping
    public Result<AdminDeviceDTO> create(@RequestBody DeviceUpsert upsert) {
        assertDeviceMenu("新增设备");
        return Result.ok(deviceService.createDevice(upsert));
    }

    /** 批量导入设备：整批校验通过后才写入，否则返回全部行级错误。 */
    @PostMapping("/import")
    public Result<DeviceImportResult> importDevices(@RequestBody List<DeviceUpsert> rows) {
        assertDeviceMenu("导入设备");
        return Result.ok(deviceService.importDevices(rows));
    }

    /** 编辑设备（设备编码可改，但必须保持唯一）。 */
    @PutMapping("/{id}")
    public Result<AdminDeviceDTO> update(@PathVariable long id, @RequestBody DeviceUpsert upsert) {
        assertDeviceMenu("编辑设备");
        return Result.ok(deviceService.updateDevice(id, upsert));
    }

    /** 绑定/换绑门店（换绑会同时补齐旧门店的解绑历史）。 */
    @PostMapping("/{id}/bind")
    public Result<Void> bind(@PathVariable long id, @RequestBody BindRequest request) {
        assertDeviceMenu("绑定设备门店");
        deviceService.bindStore(id, request.getStoreSubjectId(), request.getRemark());
        return Result.ok();
    }

    /** 解绑门店（设备回到「未绑定」，之后的上报不再归属门店）。 */
    @PostMapping("/{id}/unbind")
    public Result<Void> unbind(@PathVariable long id, @RequestBody UnbindRequest request) {
        assertDeviceMenu("解绑设备门店");
        deviceService.unbindStore(id, request.getReason());
        return Result.ok();
    }

    /** 删除设备台账（软删；需先解绑门店。删除后同 SN 的消息不会再自动建档）。 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id, @RequestParam(required = false) String reason) {
        assertDeviceMenu("删除设备");
        deviceService.deleteDevice(id, reason);
        return Result.ok();
    }
    /** 绑定历史（设备行操作弹层）。 */
    @GetMapping("/{id}/bindings")
    public Result<List<Map<String, Object>>> bindings(@PathVariable long id) {
        assertDeviceMenu("查看绑定历史");
        return Result.ok(deviceService.listBindingHistory(id));
    }

    /**
     * MNS 队列自检：判断「对方到底推没推」。
     *
     * <p>返回队列可见/不可见/延迟消息数、消费端最近心跳与最近收到消息时间、
     * 转投成功/失败计数与最近错误摘要；队列属性查询失败时 {@code ok=false} 并带原因，
     * 不抛 500（自检本身不该成为新的故障点）。默认走 10 分钟缓存，{@code refresh=1} 强制实时查询。
     */
    @GetMapping("/queue-status")
    public Result<Map<String, Object>> queueStatus(@RequestParam(required = false) Integer refresh) {
        assertDeviceMenu("查看设备队列状态");
        return Result.ok(queueStatusProvider.snapshot(refresh != null && refresh == 1));
    }
}
