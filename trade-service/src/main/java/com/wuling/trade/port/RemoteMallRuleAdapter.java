package com.wuling.trade.port;

import com.wuling.common.mq.event.SplitRuleSnapshot;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class RemoteMallRuleAdapter implements MallRulePort {
    private final RestClient serverRestClient;

    public RemoteMallRuleAdapter(@Qualifier("serverInternalRestClient") RestClient serverRestClient) {
        this.serverRestClient = serverRestClient;
    }

    @Override
    @SuppressWarnings("unchecked")
    public SplitRuleSnapshot snapshot(Long ruleId) {
        String path = ruleId == null ? "/internal/split-rule/current" : "/internal/split-rule/" + ruleId;
        Map<String, Object> body = serverRestClient.get().uri(path).retrieve().body(Map.class);
        if (body == null || body.get("ruleId") == null) return null;
        return new SplitRuleSnapshot(asLong(body.get("ruleId")), asInt(body.get("storeRatio")),
                asInt(body.get("channelRatio")), asInt(body.get("investorRatio")),
                asLong(body.get("investorThresholdAmount")), asInt(body.get("investorRatioAfter")));
    }

    private static Long asLong(Object value) {
        return value == null ? null : Long.valueOf(String.valueOf(value));
    }
    private static Integer asInt(Object value) {
        return value == null ? null : Integer.valueOf(String.valueOf(value));
    }
}
