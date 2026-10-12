package com.wuling.finance.port;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@link TradeOrderQueryPort} 的远程实现（第 7 期）。
 *
 * <p>调用 trade-service 的 {@code /internal/**} 只读接口。
 * 查询失败返回空结果（工作台统计属展示类数据，不应因下游抖动而报错）。
 */
@Component
public class RemoteTradeOrderQueryAdapter implements TradeOrderQueryPort {

    private static final Logger log = LoggerFactory.getLogger(RemoteTradeOrderQueryAdapter.class);

    private final RestClient internalRestClient;

    public RemoteTradeOrderQueryAdapter(RestClient internalRestClient) {
        this.internalRestClient = internalRestClient;
    }

    @Override
    @SuppressWarnings("unchecked")
    public long countStoreTodayOrders(Long storeSubjectId) {
        try {
            Map<String, Object> body = internalRestClient.get()
                    .uri("/internal/store-today-orders?storeSubjectId={id}", storeSubjectId)
                    .retrieve()
                    .body(Map.class);
            if (body == null || body.get("count") == null) {
                return 0L;
            }
            return Long.parseLong(String.valueOf(body.get("count")));
        } catch (Exception e) {
            log.error("查询门店今日订单数失败 store={} err={}", storeSubjectId, e.getMessage());
            return 0L;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> storeOrders(Long storeSubjectId) {
        try {
            List<Map<String, Object>> body = internalRestClient.get()
                    .uri("/internal/store-orders?storeSubjectId={id}", storeSubjectId)
                    .retrieve()
                    .body(List.class);
            return body == null ? List.of() : body;
        } catch (Exception e) {
            log.error("查询门店订单失败 store={} err={}", storeSubjectId, e.getMessage());
            return List.of();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public long countStoreQueueItems(Long storeSubjectId) {
        try {
            Map<String, Object> body = internalRestClient.get()
                    .uri("/internal/store-queue-count?storeSubjectId={id}", storeSubjectId)
                    .retrieve()
                    .body(Map.class);
            if (body == null || body.get("count") == null) {
                return 0L;
            }
            return Long.parseLong(String.valueOf(body.get("count")));
        } catch (Exception e) {
            log.error("查询门店排队件数失败 store={} err={}", storeSubjectId, e.getMessage());
            return 0L;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<Long, Long> countStoreQueueItemsBatch(List<Long> storeSubjectIds) {
        if (storeSubjectIds == null || storeSubjectIds.isEmpty()) {
            return Map.of();
        }
        String joined = storeSubjectIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        try {
            Map<String, Object> body = internalRestClient.get()
                    .uri("/internal/store-queue-counts?storeSubjectIds={ids}", joined)
                    .retrieve()
                    .body(Map.class);
            if (body == null || !(body.get("counts") instanceof Map<?, ?> raw)) {
                return Map.of();
            }
            Map<Long, Long> result = new java.util.HashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                try {
                    long storeId = Long.parseLong(String.valueOf(entry.getKey()));
                    long qty = entry.getValue() instanceof Number n ? n.longValue()
                            : Long.parseLong(String.valueOf(entry.getValue()));
                    result.put(storeId, qty);
                } catch (NumberFormatException ignored) {
                    // 单个门店解析失败不影响其它门店
                }
            }
            return result;
        } catch (Exception e) {
            log.error("批量查询门店排队件数失败 err={}", e.getMessage());
            return Map.of();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> ordersByStores(List<Long> storeSubjectIds) {
        if (storeSubjectIds == null || storeSubjectIds.isEmpty()) {
            return List.of();
        }
        String joined = storeSubjectIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        try {
            List<Map<String, Object>> body = internalRestClient.get()
                    .uri("/internal/orders-by-stores?storeSubjectIds={ids}", joined)
                    .retrieve()
                    .body(List.class);
            return body == null ? List.of() : body;
        } catch (Exception e) {
            log.error("按门店集合查询订单失败 err={}", e.getMessage());
            return List.of();
        }
    }
}
