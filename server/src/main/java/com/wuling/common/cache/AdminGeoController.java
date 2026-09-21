package com.wuling.common.cache;

import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 地址解析接口（管理端）。
 *
 * 前端不再直接调用腾讯位置服务，改为经由本接口代理：
 *   前端只提交地址，只接收经纬度；Key/SK 始终留在服务端。
 *
 * 密钥取自 app_config.tencent_map_key，读取时走 AppConfigCacheService
 * （缓存优先，TTL 1 个月，配置更新后调用 evict 主动失效）。
 */
@RestController
@RequestMapping("/api/v1/admin/geo")
public class AdminGeoController {

    private final GeoCodeService geoCodeService;

    public AdminGeoController(GeoCodeService geoCodeService) {
        this.geoCodeService = geoCodeService;
    }

    /** 地址转经纬度 */
    @PostMapping("/geocode")
    public Result<Map<String, Object>> geocode(@RequestBody Map<String, String> body) {
        String address = body == null ? null : body.get("address");
        if (address == null || address.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "地址不能为空");
        }
        GeoCodeService.GeoResult result = geoCodeService.geocode(address);
        if (result == null) {
            // 解析失败（含未配置密钥）统一返回业务异常，前端按失败提示处理
            throw new BusinessException(ResultCode.BAD_REQUEST, "地址解析失败，请检查地址或稍后重试");
        }
        return Result.ok(Map.of(
                "latitude", result.latitude(),
                "longitude", result.longitude()
        ));
    }
}