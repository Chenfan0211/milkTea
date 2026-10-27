package com.wuling.trade.port;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class RemoteMallCatalogAdapter implements MallCatalogPort {
    private static final Logger log = LoggerFactory.getLogger(RemoteMallCatalogAdapter.class);
    private final RestClient productRestClient;

    public RemoteMallCatalogAdapter(@Qualifier("productInternalRestClient") RestClient productRestClient) {
        this.productRestClient = productRestClient;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<SkuView> findSkus(List<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) return List.of();
        try {
            Object raw = productRestClient.post().uri("/internal/mall/skus/query")
                    .body(Map.of("skuIds", skuIds)).retrieve().body(List.class);
            if (!(raw instanceof List<?> list)) return List.of();
            return list.stream().filter(Map.class::isInstance).map(value -> {
                Map<String, Object> row = (Map<String, Object>) value;
                return new SkuView(asLong(row.get("skuId")), asLong(row.get("productId")),
                        text(row.get("productCode")), text(row.get("productName")), text(row.get("specValue")),
                        text(row.get("image")), asLong(row.get("salePrice"), 0L),
                        asLong(row.get("originalPrice"), 0L), asLong(row.get("costPrice"), 0L),
                        asLong(row.get("platformCommission"), 0L), asLong(row.get("distributionCommission"), 0L), asLong(row.get("supplierSubjectId")),
                        asLong(row.get("attributionStoreSubjectId")), asLong(row.get("attributionChannelSubjectId")),
                        asLong(row.get("splitRuleId")), asLong(row.get("availableStock"), 0L).intValue(),
                        Boolean.TRUE.equals(row.get("onSale")));
            }).toList();
        } catch (Exception e) {
            log.error("查询商城 SKU 失败 err={}", e.getMessage());
            throw new IllegalStateException("商城商品服务暂不可用", e);
        }
    }

    @Override
    public StockResult reserve(String orderNo, List<StockLine> lines) {
        return mutate("reserve", orderNo, lines);
    }

    @Override
    public StockResult confirm(String orderNo, List<StockLine> lines) {
        return mutate("confirm", orderNo, lines);
    }

    @Override
    public StockResult release(String orderNo, List<StockLine> lines) {
        return mutate("release", orderNo, lines);
    }

    @Override
    public StockResult restore(String orderNo, List<StockLine> lines) {
        return restore(orderNo, orderNo, lines);
    }

    @Override
    public StockResult restore(String orderNo, String bizNo, List<StockLine> lines) {
        return mutate("restore", orderNo, "AFTER_SALE", bizNo, lines);
    }

    @SuppressWarnings("unchecked")
    private StockResult mutate(String action, String orderNo, List<StockLine> lines) {
        return mutate(action, orderNo, "ORDER", orderNo, lines);
    }

    @SuppressWarnings("unchecked")
    private StockResult mutate(String action, String orderNo, String bizType, String bizNo, List<StockLine> lines) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("orderNo", orderNo);
            body.put("bizType", bizType);
            body.put("bizNo", bizNo);
            body.put("items", lines.stream().map(line -> Map.of(
                    "skuId", line.skuId(), "quantity", line.quantity())).toList());
            Map<String, Object> response = productRestClient.post()
                    .uri("/internal/mall/skus/" + action).body(body).retrieve().body(Map.class);
            boolean success = response != null && Boolean.TRUE.equals(response.get("success"));
            return success ? StockResult.ok() : StockResult.fail(
                    response == null ? "商城库存服务无响应" : text(response.get("message")));
        } catch (Exception e) {
            log.error("商城库存操作失败 action={} orderNo={} err={}", action, orderNo, e.getMessage());
            throw new IllegalStateException("商城库存服务暂不可用，请稍后重试", e);
        }
    }

    private static String text(Object value) { return value == null ? null : String.valueOf(value); }
    private static Long asLong(Object value) { return asLong(value, null); }
    private static Long asLong(Object value, Long fallback) {
        if (value == null) return fallback;
        try { return Long.valueOf(String.valueOf(value)); } catch (NumberFormatException e) { return fallback; }
    }
}

