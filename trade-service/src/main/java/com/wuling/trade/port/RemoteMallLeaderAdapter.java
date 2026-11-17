package com.wuling.trade.port;

import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * {@link MallLeaderPort} 的远程实现：调用 server 的 {@code /internal/user-leader}。
 * 已登录用户查询失败时阻止结算和下单，避免误判团长价或把佣金记给平台。
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
                    .uri(uriBuilder -> uriBuilder.path("/internal/user-leader")
                            .queryParam("userId", userId).build())
                    .retrieve().body(Map.class);
            if (body == null || !(body.get("leader") instanceof Boolean leader)) {
                throw new IllegalStateException("Invalid attribution response");
            }
            Long leaderUserId = asLong(body.get("leaderUserId"));
            Long leaderSubjectId = asLong(body.get("leaderSubjectId"));
            if ((leaderUserId == null) != (leaderSubjectId == null)
                    || (leader && !userId.equals(leaderUserId))) {
                throw new IllegalStateException("Incomplete attribution response");
            }
            return new LeaderInfo(leader, leaderUserId, leaderSubjectId);
        } catch (Exception e) {
            log.error("团长身份/归属查询失败 userId={} err={}", userId, e.getMessage());
            throw new BusinessException(ResultCode.ERROR, "团长归属暂时无法确认，请稍后重试");
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
