package com.wuling.trade.port;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * {@link MallLeaderPort} 的远程实现：调用 server 的 {@code /internal/user-leader}。
 * 失败时 fail-open（按非团长、无归属处理），避免团长链路故障阻塞商城下单。
 */
@Component
public class RemoteMallLeaderAdapter implements MallLeaderPort {

    private static final Logger log = LoggerFactory.getLogger(RemoteMallLeaderAdapter.class);

    private final RestClient serverRestClient;

    public RemoteMallLeaderAdapter(@Qualifier("serverInternalRestClient") RestClient serverRestClient) {
        this.serverRestClient = serverRestClient;
    }

    @Override
    public LeaderInfo resolve(Long userId) {
        if (userId == null) {
            return LeaderInfo.none();
        }
        try {
            Map<String, Object> body = serverRestClient.get()
                    .uri("/internal/user-leader/{userId}", userId)
                    .retrieve().body(Map.class);
            if (body == null) {
                return LeaderInfo.none();
            }
            boolean leader = Boolean.TRUE.equals(body.get("leader"));
            return new LeaderInfo(leader, asLong(body.get("leaderUserId")), asLong(body.get("leaderSubjectId")));
        } catch (Exception e) {
            log.error("团长身份/归属查询失败（按非团长处理） userId={} err={}", userId, e.getMessage());
            return LeaderInfo.none();
        }
    }

    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}