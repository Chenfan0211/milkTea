package com.wuling.product.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.product.entity.Product;
import com.wuling.product.entity.ProductStore;
import com.wuling.product.mapper.ProductMapper;
import com.wuling.product.mapper.ProductStoreMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 商品域内部查询接口（第 13 期从 server 的 InternalQueryController 迁入）。
 *
 * <p>供 trade-service 下单时校验商品、取价、判断门店上架状态。
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
        result.put("onSale", product.getOnSale());
        result.put("supplierSubjectId", product.getSupplierSubjectId());
        return result;
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
}