package com.wuling.trade.subscribe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.common.redis.RedisManager;
import com.wuling.common.redis.RedisNamespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * 小程序 access_token 提供者。
 *
 * <p>微信 access_token 全局唯一、有效期 7200 秒，必须缓存并提前刷新，
 * 否则并发拉取会让旧 token 提前失效。这里用 Redis 缓存 + JVM 内串行刷新；
 * trade-service 当前单实例部署，满足使用要求。</p>
 */
@Component
public class MiniAppAccessTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(MiniAppAccessTokenProvider.class);
    private static final Duration REFRESH_AHEAD = Duration.ofSeconds(300);

    private final WxMiniappSubscribeProperties properties;
    private final RedisManager redisManager;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Object refreshLock = new Object();

    @Autowired
    public MiniAppAccessTokenProvider(WxMiniappSubscribeProperties properties, RedisManager redisManager) {
        this(properties, redisManager, buildRestTemplate(properties.getHttpTimeoutMs()));
    }

    MiniAppAccessTokenProvider(WxMiniappSubscribeProperties properties,
                               RedisManager redisManager,
                               RestTemplate restTemplate) {
        this.properties = properties;
        this.redisManager = redisManager;
        this.restTemplate = restTemplate;
    }

    /** 取 access_token；缓存命中直接返回，否则刷新。 */
    public String getAccessToken() {
        String cached = redisGet();
        if (StringUtils.hasText(cached)) {
            return cached;
        }
        synchronized (refreshLock) {
            cached = redisGet();
            if (StringUtils.hasText(cached)) {
                return cached;
            }
            return refreshAccessToken();
        }
    }

    /** token 失效等场景清掉缓存，让下一次发送重新获取。 */
    public void invalidate() {
        try {
            redisManager.template(RedisNamespace.CACHE).delete(cacheKey());
        } catch (Exception e) {
            log.warn("清除小程序 access_token 缓存失败: {}", e.getMessage());
        }
    }

    String cacheKey() {
        return RedisNamespace.CACHE.key("wx:miniapp:access_token:" + properties.getAppId());
    }

    private String redisGet() {
        try {
            return redisManager.template(RedisNamespace.CACHE).opsForValue().get(cacheKey());
        } catch (Exception e) {
            log.warn("读取小程序 access_token 缓存失败: {}", e.getMessage());
            return null;
        }
    }

    private String refreshAccessToken() {
        String url = properties.getAccessTokenUrl()
                + "?grant_type=client_credential"
                + "&appid=" + properties.getAppId()
                + "&secret=" + properties.getAppSecret();
        String body;
        try {
            body = restTemplate.getForObject(url, String.class);
        } catch (Exception e) {
            throw new IllegalStateException("获取小程序 access_token 失败", e);
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            int errcode = node.path("errcode").asInt(0);
            String accessToken = node.path("access_token").asText(null);
            if (errcode != 0 || !StringUtils.hasText(accessToken)) {
                throw new IllegalStateException("获取小程序 access_token 失败 errcode=" + errcode);
            }
            long expiresIn = node.path("expires_in").asLong(7200L);
            long ttlSeconds = Math.max(60L, expiresIn - REFRESH_AHEAD.getSeconds());
            try {
                redisManager.template(RedisNamespace.CACHE).opsForValue()
                        .set(cacheKey(), accessToken, Duration.ofSeconds(ttlSeconds));
            } catch (Exception e) {
                // 缓存写入失败不阻断本次发送，只是下次会重新拉取
                log.warn("写入小程序 access_token 缓存失败: {}", e.getMessage());
            }
            return accessToken;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("解析小程序 access_token 响应失败", e);
        }
    }

    private static RestTemplate buildRestTemplate(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeout = timeoutMs > 0 ? timeoutMs : 3000;
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return new RestTemplate(factory);
    }
}