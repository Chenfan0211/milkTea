package com.wuling.common.cache;

import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 小程序端地理位置接口（服务端代理腾讯位置服务）。
 *
 * <p>为什么需要本接口：小程序端不应持有腾讯 Key/SK。这里只接收坐标、
 * 只返回解析结果，密钥始终留在服务端（app_config.tencent_map_key）。
 *
 * <p>与 {@link AdminGeoController} 的区别：那个面向管理端做「地址 -> 经纬度」，
 * 本接口面向小程序端做「经纬度 -> 地址」与「真实距离」，属于 C 端高频只读接口。
 *
 * <p>降级策略：未配置密钥或腾讯侧失败时，统一返回空结果而非报错，
 * 由前端回落到直线距离估算，保证门店列表始终可用。
 */
@RestController
@RequestMapping("/api/v1/app/geo")
public class AppGeoController {

    private final GeoCodeService geoCodeService;

    public AppGeoController(GeoCodeService geoCodeService) {
        this.geoCodeService = geoCodeService;
    }

    /** 逆地址解析：经纬度 -> 地址，用于展示「当前位置」 */
    @PostMapping("/regeo")
    public Result<Map<String, Object>> regeo(@RequestBody Map<String, Object> body) {
        double latitude = readDouble(body, "latitude");
        double longitude = readDouble(body, "longitude");
        if (!isValidCoordinate(latitude, longitude)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "经纬度不合法");
        }
        GeoCodeService.ReverseResult result = geoCodeService.reverseGeocode(latitude, longitude);
        if (result == null) {
            // 解析失败不视为错误：前端按「未能获取地址」处理
            return Result.ok(Map.of());
        }
        return Result.ok(Map.of(
                "address", nullToEmpty(result.address()),
                "formattedAddress", nullToEmpty(result.formattedAddress()),
                "city", nullToEmpty(result.city())
        ));
    }

    /** 距离矩阵：用户位置 -> 多个门店的真实驾车距离 */
    @PostMapping("/distance")
    public Result<Map<String, Object>> distance(@RequestBody Map<String, Object> body) {
        double latitude = readDouble(body, "latitude");
        double longitude = readDouble(body, "longitude");
        if (!isValidCoordinate(latitude, longitude)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "经纬度不合法");
        }
        List<String> destinations = readDestinations(body);
        List<GeoCodeService.DistanceEntry> entries =
                geoCodeService.distanceMatrix(latitude, longitude, destinations);

        List<Map<String, Object>> items = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            GeoCodeService.DistanceEntry entry = entries.get(i);
            items.add(Map.of(
                    "index", i,
                    "distanceKm", entry.distanceKm(),
                    "durationMinutes", entry.durationMinutes()
            ));
        }
        return Result.ok(Map.of("items", items));
    }

    /** 解析请求体中的 destination 数组（元素为 "纬度,经度"） */
    @SuppressWarnings("unchecked")
    private List<String> readDestinations(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("destinations");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "destinations 不能为空");
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object item : (List<Object>) list) {
            if (item == null) {
                continue;
            }
            String text = String.valueOf(item).trim();
            if (text.isEmpty()) {
                continue;
            }
            if (parseCoordinate(text) == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "destinations 坐标格式应为 纬度,经度");
            }
            result.add(text);
        }
        if (result.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "destinations 不能为空");
        }
        return result;
    }

    /** 解析单个乘数：非法值一律视为 0，交由 isValidCoordinate 统一拦截 */
    private double readDouble(Map<String, Object> body, String field) {
        Object raw = body == null ? null : body.get(field);
        if (raw == null) {
            return 0;
        }
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 格式校验："纬度,经度"，两段均可解析为数字 */
    private double[] parseCoordinate(String text) {
        String[] parts = text.split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new double[]{Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 坐标合法性：纬度 -90~90、经度 -180~180，且不同时为 0。
     * 同时为 0（几内亚湾）几乎必然是「未定位 / 空值」造成的脏数据，直接拒绝。
     */
    private boolean isValidCoordinate(double latitude, double longitude) {
        if (latitude == 0 && longitude == 0) {
            return false;
        }
        return latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
