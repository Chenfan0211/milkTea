package com.wuling.product.port;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class RemoteSplitRuleValidationAdapter implements SplitRuleValidationPort {
    private final RestClient serverRestClient;

    public RemoteSplitRuleValidationAdapter(@Qualifier("serverInternalRestClient") RestClient serverRestClient) {
        this.serverRestClient = serverRestClient;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean isValid(Long ruleId) {
        try {
            String path = ruleId == null ? "/internal/split-rule/current" : "/internal/split-rule/" + ruleId;
            Map<String, Object> body = serverRestClient.get().uri(path).retrieve().body(Map.class);
            return body != null && Boolean.TRUE.equals(body.get("found"));
        } catch (Exception e) {
            return false;
        }
    }
}
