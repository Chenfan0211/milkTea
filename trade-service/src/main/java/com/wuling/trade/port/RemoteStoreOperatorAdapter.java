package com.wuling.trade.port;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * {@link StoreOperatorPort} 的远程实现。
 *
 * <p>调用 server 的 {@code /internal/store-operator-check}。
 * {@code /internal/**} 不在网关路由范围内，且各服务仅监听 127.0.0.1，
 * 因此不会经公网暴露。
 */
@Component
public class RemoteStoreOperatorAdapter implements StoreOperatorPort {

    private static final Logger log = LoggerFactory.getLogger(RemoteStoreOperatorAdapter.class);

    private final RestClient serverRestClient;

    public RemoteStoreOperatorAdapter(@Qualifier("serverInternalRestClient") RestClient serverRestClient) {
        this.serverRestClient = serverRestClient;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean isStoreOperator(Long userId, Long subjectId) {
        if (userId == null || subjectId == null) {
            return false;
        }
        try {
            Map<String, Object> body = serverRestClient.get()
                    .uri("/internal/store-operator-check?userId={u}&subjectId={s}", userId, subjectId)
                    .retrieve()
                    .body(Map.class);
            boolean allowed = body != null && Boolean.TRUE.equals(body.get("allowed"));
            if (!allowed) {
                log.warn("门店经营者归属校验未通过 userId={} subjectId={}", userId, subjectId);
            }
            return allowed;
        } catch (Exception e) {
            // fail-closed：内网异常时拒绝核销，避免越权放行
            log.error("门店经营者归属校验失败（按拒绝处理） userId={} subjectId={} err={}",
                    userId, subjectId, e.getMessage());
            return false;
        }
    }
}