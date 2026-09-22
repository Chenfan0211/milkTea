package com.wuling.trade.port;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * {@link UserQueryPort} 的远程实现（第 14 期支付接入）。
 *
 * <p>调用 user-service 的 {@code /internal/users/{userId}/openid}。
 * {@code /internal/**} 不在网关路由范围内，且用户服务仅监听 127.0.0.1，
 * 因此不会经公网暴露。
 *
 * <p>复用第 7 期建立的内部 RestClient 模式（服务名寻址 + 短超时），
 * 但指向 user-service，故需单独的 base-url 配置。
 */
@Component
public class RemoteUserQueryAdapter implements UserQueryPort {

    private static final Logger log = LoggerFactory.getLogger(RemoteUserQueryAdapter.class);

    private final RestClient userRestClient;

    public RemoteUserQueryAdapter(@Qualifier("userInternalRestClient") RestClient userRestClient) {
        this.userRestClient = userRestClient;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String findOpenid(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            Map<String, Object> body = userRestClient.get()
                    .uri("/internal/users/{id}/openid", userId)
                    .retrieve()
                    .body(Map.class);
            if (body == null || !Boolean.TRUE.equals(body.get("found"))) {
                log.warn("用户 openid 不存在 userId={}", userId);
                return null;
            }
            Object openId = body.get("openId");
            // 日志脱敏：openid 属用户标识，只记录存在性
            log.debug("已获取用户 openid userId={}", userId);
            return openId == null ? null : String.valueOf(openId);
        } catch (Exception e) {
            log.error("远程查询用户 openid 失败 userId={} err={}", userId, e.getMessage());
            return null;
        }
    }
}
