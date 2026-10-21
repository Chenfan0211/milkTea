package com.wuling.device.controller;

import com.wuling.common.api.PageResult;
import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.device.dto.AdminDeviceDTO;
import com.wuling.device.service.DeviceService;
import com.wuling.security.CurrentUser;
import com.wuling.subject.service.StoreOperatorService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 小程序端：门店经营角色的设备台账与冲泡记录（<b>只读</b>）。
 *
 * <p><b>为什么单开一个控制器</b>：后台 {@link AdminDeviceController} 走
 * {@code /api/v1/admin/**} 的运营账号鉴权，门店店员没有后台账号；
 * 而设备台账的维护（新增/绑定/解绑/删除）是运营职责，门店只需要看。
 * 因此门店侧只暴露两个只读查询，写操作仍留在后台。
 *
 * <p><b>越权防护（本接口的核心）</b>：{@code storeSubjectId} 由前端传入，
 * 服务端必须确认当前登录用户确实经营该门店（{@link StoreOperatorService}），
 * 否则任何登录用户改个 ID 就能看到别家门店的设备与冲泡记录。
 * 数据范围一律以服务端解析出的门店为准，前端传的其它筛选条件不能放大范围。
 *
 * <p><b>鉴权链路</b>：{@code /api/v1/app/workbench/**} 已注册小程序鉴权拦截器
 * （见 server 的 {@code WebConfig}），未带 token 时在拦截器层就返回 401；
 * 控制器内的 {@link CurrentUser#require()} 是第二道保险（防止将来路径注册漏配），
 * 也保证本控制器脱离 Web 层被直接调用时不会「默认放行」。
 *
 * <p><b>网关无需改动</b>：新路径 {@code /api/v1/app/workbench/store/{id}/devices}
 * 与 {@code .../brew-records} 由网关的 {@code /api/v1/**} 兜底路由进入 server
 * （设备域就在 server），且不与 trade-service（核销路径
 * {@code /api/v1/app/workbench/store/{id}/verify-records}）与 product-service
 * （门店选品路径 {@code /api/v1/app/workbench/store/{id}/products}）的精确路由冲突。
 */
@RestController
@RequestMapping("/api/v1/app/workbench/store/{storeSubjectId}")
public class AppDeviceController {

    /**
     * 门店侧单页上限。
     *
     * <p>门店可能被绑定多台设备、冲泡记录条数增长很快，若允许前端传大 size，
     * 一次请求就能把整表拉走，既压数据库也让小程序渲染卡顿，故在服务端封顶。
     */
    static final long MAX_PAGE_SIZE = 50;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final DeviceService deviceService;
    private final StoreOperatorService storeOperatorService;

    public AppDeviceController(DeviceService deviceService, StoreOperatorService storeOperatorService) {
        this.deviceService = deviceService;
        this.storeOperatorService = storeOperatorService;
    }

    /**
     * 本店设备清单（当前绑定在本店名下的设备）。
     *
     * <p>默认 20 条/页；{@code size} 超过 {@value #MAX_PAGE_SIZE} 时按上限截断。
     */
    @GetMapping("/devices")
    public Result<PageResult<AdminDeviceDTO>> devices(@PathVariable Long storeSubjectId,
                                                     @RequestParam(defaultValue = "1") long current,
                                                     @RequestParam(defaultValue = "20") long size) {
        requireStoreOperator(storeSubjectId);
        return Result.ok(deviceService.pageStoreDevices(
                Math.max(current, 1), capSize(size), storeSubjectId));
    }

    /**
     * 本店设备概览：{@code { total, online }}。
     *
     * <p>页面头部「共 N 台 · 在线 M 台」直接用这两个数，避免用分页条数估算而偏小。
     */
    @GetMapping("/devices/summary")
    public Result<Map<String, Object>> deviceSummary(@PathVariable Long storeSubjectId) {
        requireStoreOperator(storeSubjectId);
        return Result.ok(deviceService.storeDeviceSummary(storeSubjectId));
    }

    /**
     * 本店冲泡记录（按写入时刻的门店快照过滤，设备换绑后历史仍归当时门店）。
     *
     * @param deviceSn 设备编码精确筛选，可空
     * @param keyword  关键词模糊搜索（茶品名/编码、设备编码/ID/别名、客户代码、序列号），可空
     * @param from     起始日（含），格式 {@code yyyy-MM-dd}
     * @param to       结束日（含），格式 {@code yyyy-MM-dd}；内部换算成次日 00:00:00 作为开区间上界
     */
    @GetMapping("/brew-records")
    public Result<PageResult<Map<String, Object>>> brewRecords(@PathVariable Long storeSubjectId,
                                                              @RequestParam(defaultValue = "1") long current,
                                                              @RequestParam(defaultValue = "20") long size,
                                                              @RequestParam(required = false) String deviceSn,
                                                              @RequestParam(required = false) String keyword,
                                                              @RequestParam(required = false) String from,
                                                              @RequestParam(required = false) String to) {
        requireStoreOperator(storeSubjectId);
        return Result.ok(deviceService.pageStoreBrewRecords(
                Math.max(current, 1), capSize(size), storeSubjectId,
                deviceSn, keyword, dayStart(from), dayEndExclusive(to)));
    }

    // ---------- 内部工具 ----------

    /** 当前登录用户必须经营该门店，否则 403（不区分「门店不存在」与「无权限」，避免探测他人门店）。 */
    private void requireStoreOperator(Long storeSubjectId) {
        Long userId = CurrentUser.require();
        if (storeSubjectId == null || !storeOperatorService.canOperate(userId, storeSubjectId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权查看该门店数据，请确认已开通门店经营角色");
        }
    }

    private long capSize(long size) {
        if (size <= 0) {
            return 20;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private LocalDateTime dayStart(String day) {
        LocalDate date = parseDay(day);
        return date == null ? null : date.atStartOfDay();
    }

    /** 结束日 → 次日 00:00:00（开区间上界），保证结束日当天 23:59:59 的记录不被漏掉。 */
    private LocalDateTime dayEndExclusive(String day) {
        LocalDate date = parseDay(day);
        return date == null ? null : date.plusDays(1).atStartOfDay();
    }

    private LocalDate parseDay(String day) {
        if (!StringUtils.hasText(day)) {
            return null;
        }
        try {
            return LocalDate.parse(day.trim(), DAY);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "日期格式应为 yyyy-MM-dd：" + day);
        }
    }
}
