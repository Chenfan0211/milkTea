package com.wuling.user.tongue;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * product-service 商品卡内部适配器。
 *
 * <p>固定契约：GET /internal/product/cards?names=a&amp;names=b -&gt;
 * {@code [{productId,name,image,price,originalPrice,tags}]}（只返回在售商品）。
 *
 * <p><b>降级原则</b>：商品服务异常/超时只记 warn 并返回空 Map，
 * 由调用方按「未匹配」处理，绝不阻断舌象报告主流程。
 */
@Component
public class ProductCardClient {

    private static final Logger log = LoggerFactory.getLogger(ProductCardClient.class);

    private final RestClient restClient;

    public ProductCardClient(@Qualifier("productCardRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * 按商品名批量查询商城商品卡。
     *
     * @return key 为归一化商品名（去空格、小写）的商品卡映射；异常时返回空 Map
     */
    public Map<String, ProductCard> cardsByNames(List<String> names) {
        Set<String> unique = new LinkedHashSet<>();
        for (String name : names == null ? List.<String>of() : names) {
            if (StringUtils.hasText(name)) {
                unique.add(name.trim());
            }
        }
        if (unique.isEmpty()) {
            return Map.of();
        }
        try {
            JsonNode root = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/product/cards")
                            .queryParam("names", String.join(",", unique))
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            return toCards(root);
        } catch (Exception e) {
            log.warn("舌象商品卡查询失败，按未匹配降级 type={} message={}",
                    e.getClass().getSimpleName(), e.getMessage());
            return Map.of();
        }
    }

    private static Map<String, ProductCard> toCards(JsonNode root) {
        JsonNode data = root;
        if (data != null && data.isObject() && data.has("data")) {
            data = data.get("data");
        }
        if (data == null || !data.isArray()) {
            return Map.of();
        }
        Map<String, ProductCard> result = new LinkedHashMap<>();
        for (JsonNode node : data) {
            String name = text(node, "name");
            if (!StringUtils.hasText(name)) {
                continue;
            }
            result.put(TongueReportParser.normalizeName(name), new ProductCard(
                    text(node, "productId"),
                    name.trim(),
                    text(node, "image"),
                    number(node, "price"),
                    number(node, "originalPrice"),
                    jsonList(node, "tags"),
                    jsonList(node, "suitableConstitutions")
            ));
        }
        return result;
    }

    /** 解析 JSON 数组字段（tags / suitableConstitutions 共用）。 */
    private static List<String> jsonList(JsonNode node, String field) {
        JsonNode tags = node == null ? null : node.get(field);
        if (tags == null || tags.isNull()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        if (tags.isArray()) {
            for (JsonNode tag : tags) {
                if (tag != null && tag.isTextual() && StringUtils.hasText(tag.asText())) {
                    result.add(tag.asText().trim());
                }
            }
            return List.copyOf(result);
        }
        String raw = tags.isTextual() ? tags.asText() : null;
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        for (String part : raw.replace("[", "").replace("]", "").replace("\"", "").split(",")) {
            if (StringUtils.hasText(part)) {
                result.add(part.trim());
            }
        }
        return List.copyOf(result);
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isTextual() ? value.asText() : value.toString();
    }

    private static Long number(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        return value == null || value.isNull() || !value.isNumber() ? null : value.asLong();
    }
}