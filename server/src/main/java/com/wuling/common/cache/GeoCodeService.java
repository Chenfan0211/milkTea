package com.wuling.common.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 腾讯位置服务（服务端代理）。
 *
 * 已接入能力：
 *   - geocode         地址 -> 经纬度（/ws/geocoder/v1/）
 *   - reverseGeocode  经纬度 -> 结构化地址（/ws/geocoder/v1/）
 *   - distanceMatrix  起终点真实驾车距离（/ws/distance/v1/matrix）
 *
 * 为什么放在服务端：
 *   调用腾讯 geocoder 需要在请求中携带 Key 与 SK 签名。若在前端直接调用，
 *   Key/SK 会随构建产物下发，任何人都能从浏览器中取出并冒用配额。
 *   因此密钥只保存在服务端（app_config.tencent_map_key），前端仅传地址、
 *   仅接收经纬度，密钥不出服务器。
 *
 * 签名规则（与腾讯官方 SN 校验一致）：
 *   1) 参数按键名字典序升序排列；
 *   2) 拼接为 path?k1=v1&k2=v2 后追加 SK，value 使用原始值（不 URL 编码）；
 *   3) 对整串做 MD5，得到 32 位小写十六进制 sig。
 */
@Service
public class GeoCodeService {

    private static final Logger log = LoggerFactory.getLogger(GeoCodeService.class);

    private static final String GEOCODER_PATH = "/ws/geocoder/v1/";
    private static final String DISTANCE_PATH = "/ws/distance/v1/matrix";
    private static final String GEOCODER_HOST = "https://apis.map.qq.com";

    /** 密钥在 app_config 中的 config_key */
    private static final String CONFIG_KEY = "tencent_map_key";

    /** 腾讯距离矩阵单次最多 200 个终点，超出部分截断，避免请求被拒 */
    private static final int MAX_DISTANCE_POINTS = 200;

    private final AppConfigCacheService configCacheService;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GeoCodeService(AppConfigCacheService configCacheService) {
        this.configCacheService = configCacheService;
    }

    /** 地址解析结果 */
    public record GeoResult(double latitude, double longitude) {
    }

    /** 解析地址为经纬度。 */
    public GeoResult geocode(String address) {
        if (!StringUtils.hasText(address)) {
            return null;
        }
        String trimmed = address.trim();
        // 签名串用原始值，请求串用 URL 编码值
        Map<String, String> params = new TreeMap<>();
        params.put("address", trimmed);

        JsonNode root = callTencent(GEOCODER_PATH, params);
        if (root == null) {
            return null;
        }
        JsonNode location = root.path("result").path("location");
        if (location.isMissingNode()) {
            return null;
        }
        return new GeoResult(location.path("lat").asDouble(), location.path("lng").asDouble());
    }

    /** 逆地址解析结果：经纬度对应的人类可读地址。 */
    public record ReverseResult(String address, String formattedAddress, String city) {
    }

    /**
     * 逆地址解析：经纬度 -> 结构化地址。
     *
     * <p>用于「用户当前位置」展示与门店就近归属判断，密钥同样不出服务器。
     *
     * @param latitude  纬度
     * @param longitude 经度
     * @return 解析结果；未配置密钥或腾讯返回失败时返回 null
     */
    public ReverseResult reverseGeocode(double latitude, double longitude) {
        Map<String, String> params = new TreeMap<>();
        params.put("location", latitude + "," + longitude);
        params.put("get_poi", "1");

        JsonNode root = callTencent(GEOCODER_PATH, params);
        if (root == null) {
            return null;
        }
        JsonNode result = root.path("result");
        if (result.isMissingNode()) {
            return null;
        }
        return new ReverseResult(
                result.path("address").asText(""),
                result.path("formatted_addresses").path("recommend").asText(""),
                result.path("address_component").path("city").asText(""));
    }

    /** 距离矩阵结果：到每个终点的真实距离（公里）与预估耗时（分钟）。 */
    public record DistanceEntry(double distanceKm, double durationMinutes) {
    }

    /**
     * 距离矩阵：计算起点到多个终点的真实驾车距离。
     *
     * <p>替代前端的 Haversine 直线估算，给出更贴近实际的距离与耗时。
     * 腾讯单次请求最多支持 1 个起点 -> 200 个终点，这里按 200 截断保护。
     *
     * @param fromLat 起点纬度
     * @param fromLng 起点经度
     * @param toPoints 终点坐标（"纬度,经度" 形式，顺序与返回结果一致）
     * @return 与 {@code toPoints} 等长的结果列表；未配置密钥或解析失败时返回空列表
     */
    public List<DistanceEntry> distanceMatrix(double fromLat, double fromLng, List<String> toPoints) {
        if (toPoints == null || toPoints.isEmpty()) {
            return List.of();
        }
        List<String> capped = toPoints.size() > MAX_DISTANCE_POINTS
                ? toPoints.subList(0, MAX_DISTANCE_POINTS)
                : toPoints;

        Map<String, String> params = new TreeMap<>();
        params.put("mode", "driving");
        params.put("from", fromLat + "," + fromLng);
        params.put("to", String.join(";", capped));

        JsonNode root = callTencent(DISTANCE_PATH, params);
        if (root == null) {
            return List.of();
        }
        JsonNode rows = root.path("result").path("rows");
        if (!rows.isArray() || rows.isEmpty()) {
            return List.of();
        }
        JsonNode elements = rows.get(0).path("elements");
        if (!elements.isArray()) {
            return List.of();
        }
        List<DistanceEntry> entries = new ArrayList<>(elements.size());
        for (JsonNode element : elements) {
            entries.add(new DistanceEntry(
                    element.path("distance").asDouble(0) / 1000.0,
                    element.path("duration").asDouble(0) / 60.0));
        }
        return entries;
    }

    /**
     * 统一调用腾讯 WebService 接口。
     *
     * <p>集中处理：密钥读取、SN 签名、请求发送、status 校验。
     * 任何失败都以 {@code null} 返回，由调用方决定降级策略，不向接口层抛异常。
     */
    private JsonNode callTencent(String path, Map<String, String> params) {
        Map<String, Object> config = configCacheService.readMap(CONFIG_KEY);
        if (config == null) {
            log.warn("腾讯地图密钥未配置，跳过调用: path={}", path);
            return null;
        }
        Object keyValue = config.get("key");
        if (keyValue == null || String.valueOf(keyValue).isBlank()) {
            log.warn("腾讯地图 Key 为空，跳过调用: path={}", path);
            return null;
        }
        params.put("key", String.valueOf(keyValue));
        String sk = config.get("sk") == null ? "" : String.valueOf(config.get("sk"));

        String rawQuery = buildQuery(params, false);
        String urlQuery = buildQuery(params, true);

        StringBuilder url = new StringBuilder(GEOCODER_HOST)
                .append(path)
                .append("?")
                .append(urlQuery);
        if (!sk.isBlank()) {
            url.append("&sig=").append(md5(path + "?" + rawQuery + sk));
        }

        try {
            String body = restTemplate.getForObject(url.toString(), String.class);
            if (body == null) {
                return null;
            }
            JsonNode root = objectMapper.readTree(body);
            if (root.path("status").asInt(-1) != 0) {
                log.warn("腾讯位置服务调用失败: path={} status={} message={}",
                        path, root.path("status").asInt(), root.path("message").asText(""));
                return null;
            }
            return root;
        } catch (Exception e) {
            log.warn("调用腾讯位置服务异常: path={} error={}", path, e.getMessage());
            return null;
        }
    }

    private String buildQuery(Map<String, String> params, boolean urlEncode) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) {
                sb.append("&");
            }
            first = false;
            sb.append(entry.getKey()).append("=");
            sb.append(urlEncode
                    ? URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)
                    : entry.getValue());
        }
        return sb.toString();
    }

    /** 32 位小写十六进制 MD5 */
    private String md5(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 计算失败", e);
        }
    }
}