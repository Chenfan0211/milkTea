package com.wuling.product.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.product.entity.Product;
import com.wuling.product.entity.ProductStore;
import com.wuling.product.mapper.ProductMapper;
import com.wuling.product.mapper.ProductStoreMapper;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 商品域内部查询接口（第 13 期从 server 的 InternalQueryController 迁入）。
 *
 * <p>供 trade-service 下单时校验商品、取价、判断门店上架状态；
 * 供 user-service 舌象报告按商品名匹配商城商品卡。
 *
 * <p><b>为什么迁到 product-service</b>：商品拆出后若仍由 server 提供
 * {@code /internal/product*}，会形成 trade → server → product 的绕行，
 * 且 server 需反向依赖 product 域（拆分不彻底）。迁入本服务后，
 * 调用链为 trade → product-service，职责单一。
 *
 * <p><b>安全约束</b>（与 server 的 internal 约定一致）：
 * <ul>
 *   <li>{@code /internal/**} 不在网关路由范围（网关仅路由 {@code /api/v1/**} 与 {@code /auth/**}）；</li>
 *   <li>服务仅监听 127.0.0.1，跨服务调用走本机/内网；</li>
 *   <li>只读，不含写操作。</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal")
public class ProductInternalQueryController {

    /** 单次商品卡查询的名称上限：防止报告侧异常数据把 IN 查询放大。 */
    private static final int MAX_CARD_NAMES = 20;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProductMapper productMapper;
    private final ProductStoreMapper productStoreMapper;

    public ProductInternalQueryController(ProductMapper productMapper,
                                          ProductStoreMapper productStoreMapper) {
        this.productMapper = productMapper;
        this.productStoreMapper = productStoreMapper;
    }

    /**
     * 查询商品（供 trade 下单校验与取价）。
     *
     * @param productId 业务商品 ID（如 classic-001）
     * @return 商品信息；不存在时返回空 Map
     */
    @GetMapping("/product")
    public Map<String, Object> product(@RequestParam String productId) {
        Product product = productMapper.selectOne(new LambdaQueryWrapper<Product>()
                .eq(Product::getProductId, productId).last("limit 1"));
        Map<String, Object> result = new HashMap<>();
        if (product == null) {
            return result;
        }
        result.put("id", product.getId());
        result.put("productId", product.getProductId());
        result.put("name", product.getName());
        result.put("price", product.getPrice());
        result.put("originalPrice", product.getOriginalPrice());
        // 商品图：供 trade 下单时快照进 order_item.image，小程序订单详情据此展示
        result.put("image", product.getImage());
        result.put("onSale", product.getOnSale());
        result.put("supplierSubjectId", product.getSupplierSubjectId());
        // 平台提成与成本价：供 trade 核销分账时按商品明细分摊（每件一份）
        result.put("platformCommission", product.getPlatformCommission());
        result.put("costPrice", product.getCostPrice());
        result.put("storedValuePrice", product.getStoredValuePrice());
        return result;
    }

    /**
     * 批量按商品名查询「商品卡」（供 user-service 舌象报告推荐商品匹配）。
     *
     * <p>匹配口径：只返回在售商品（逻辑删除由 {@code @TableLogic} 自动过滤），
     * 名称按「去空白 + 忽略大小写」归一化后精确匹配；未命中的名称直接不出现在结果里，
     * 由调用方按「匹配不到就不渲染」处理，不会伪造价格或图片。
     *
     * @param names 报告侧商品名，支持逗号分隔或重复传参；超过 {@value #MAX_CARD_NAMES} 个只取前 N 个
     * @return 命中的商品卡列表（productId/name/image/price/originalPrice/tags）
     */
    @GetMapping("/product/cards")
    public List<Map<String, Object>> productCards(
            @RequestParam(name = "names", required = false) List<String> names) {
        Map<String, String> requested = new LinkedHashMap<>();
        for (String name : names == null ? List.<String>of() : names) {
            if (StringUtils.hasText(name)) {
                String trimmed = name.trim();
                requested.putIfAbsent(normalizeName(trimmed), trimmed);
            }
        }
        if (requested.isEmpty()) {
            return List.of();
        }
        List<String> queryNames = requested.values().stream().limit(MAX_CARD_NAMES).toList();
        List<Product> products = productMapper.selectList(new LambdaQueryWrapper<Product>()
                .eq(Product::getOnSale, 1)
                .in(Product::getName, queryNames));
        if (products == null || products.isEmpty()) {
            return List.of();
        }
        Map<String, Map<String, Object>> cards = new LinkedHashMap<>();
        for (Product product : products) {
            String key = normalizeName(product.getName());
            if (!requested.containsKey(key) || cards.containsKey(key)) {
                continue;
            }
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("productId", product.getProductId());
            card.put("name", product.getName());
            card.put("image", product.getImage());
            card.put("price", product.getPrice());
            card.put("originalPrice", product.getOriginalPrice());
            card.put("tags", parseJsonList(product.getTags()));
            card.put("suitableConstitutions", parseJsonList(product.getSuitableConstitutions()));
            cards.put(key, card);
        }
        return List.copyOf(cards.values());
    }

    /**
     * 校验商品是否已在门店上架（供 trade 下单前置校验）。
     *
     * @return { "inStore": true/false }
     */
    @GetMapping("/product-in-store")
    public Map<String, Object> productInStore(@RequestParam Long productId,
                                              @RequestParam Long storeSubjectId) {
        Long count = productStoreMapper.selectCount(new LambdaQueryWrapper<ProductStore>()
                .eq(ProductStore::getProductId, productId)
                .eq(ProductStore::getStoreSubjectId, storeSubjectId));
        return Map.of("inStore", count != null && count > 0);
    }

    /** 与 user-service 侧一致的商品名归一化：去空白 + 转小写。 */
    private static String normalizeName(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim().toLowerCase(Locale.ROOT);
    }

    /** product.tags / suitable_constitutions 存 JSON 数组文本（如 ["年度热销"]），解析失败按空数组处理。 */
    private static List<String> parseJsonList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}