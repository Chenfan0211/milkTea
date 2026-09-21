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
import java.util.Map;
import java.util.TreeMap;

/**
 * 腾讯位置服务地址解析（服务端代理）。
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
    private static final String GEOCODER_HOST = "https://apis.map.qq.com";

    private final AppConfigCacheService configCacheService;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GeoCodeService(AppConfigCacheService configCacheService) {
        this.configCacheService = configCacheService;
    }

    /** 地址解析结果 */
    public record GeoResult(double latitude, double longitude) {
    }

    /**
     * 解析地址为经纬度。
     *
     * @param address 结构化地址
     * @return 解析结果；未配置密钥、地址为空或腾讯返回失败时返回 null
     */
    public GeoResult geocode(String address) {
        if (!StringUtils.hasText(address)) {
            return null;
        }
        Map<String, Object> config = configCacheService.readMap("tencent_map_key");
        if (config == null) {
            log.warn("腾讯地图密钥未配置，跳过地址解析");
            return null;
        }
        Object keyValue = config.get("key");
        if (keyValue == null || String.valueOf(keyValue).isBlank()) {
            log.warn("腾讯地图 Key 为空，跳过地址解析");
            return null;
        }
        String key = String.valueOf(keyValue);
        String sk = config.get("sk") == null ? "" : String.valueOf(config.get("sk"));

        String trimmed = address.trim();

        // 签名串用原始值，请求串用 URL 编码值
        Map<String, String> params = new TreeMap<>();
        params.put("address", trimmed);
        params.put("key", key);

        String rawQuery = buildQuery(params, false);
        String urlQuery = buildQuery(params, true);

        StringBuilder url = new StringBuilder(GEOCODER_HOST)
                .append(GEOCODER_PATH)
                .append("?")
                .append(urlQuery);
        if (!sk.isBlank()) {
            url.append("&sig=").append(md5(GEOCODER_PATH + "?" + rawQuery + sk));
        }

        try {
            String body = restTemplate.getForObject(url.toString(), String.class);
            if (body == null) {
                return null;
            }
            JsonNode root = objectMapper.readTree(body);
            if (root.path("status").asInt(-1) != 0) {
                log.warn("腾讯地址解析失败: status={} message={}",
                        root.path("status").asInt(), root.path("message").asText(""));
                return null;
            }
            JsonNode location = root.path("result").path("location");
            if (location.isMissingNode()) {
                return null;
            }
            return new GeoResult(location.path("lat").asDouble(), location.path("lng").asDouble());
        } catch (Exception e) {
            // 外部服务异常不应抛出到接口层，由调用方按 null 处理
            log.warn("调用腾讯地址解析异常: {}", e.getMessage());
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