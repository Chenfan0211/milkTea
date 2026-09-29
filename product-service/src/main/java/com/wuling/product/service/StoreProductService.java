package com.wuling.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.common.api.PageResult;
import com.wuling.product.dto.StoreProductDetailDTO;
import com.wuling.product.dto.StoreProductDTO;
import com.wuling.product.entity.Product;
import com.wuling.product.entity.ProductCategory;
import com.wuling.product.entity.ProductStore;
import com.wuling.product.mapper.ProductCategoryMapper;
import com.wuling.product.mapper.ProductMapper;
import com.wuling.product.mapper.ProductStoreMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 门店选品（小程序端）。
 *
 * <p><b>数据口径（2026-09 确认）</b>：
 * <ul>
 *   <li>基础数据 = 平台已上架商品（{@code product.on_sale = 1}）；</li>
 *   <li>{@code listed=true} —— {@code product_store} 有「本门店 + 本商品」记录；</li>
 *   <li>{@code listed=false} —— 无记录。</li>
 * </ul>
 * 「已上架」Tab 展示本店已上架商品；「已下架」Tab 展示本店未上架商品。
 *
 * <p>与旧的本地 Storage 逻辑不同：这里以后端为准，多设备一致，
 * 且门店下架后消费端点单页实时生效。
 */
@Service
public class StoreProductService {

    /** 单页最大条数，防止前端传超大 size 拖垮查询。 */
    private static final long MAX_PAGE_SIZE = 100L;

    private final ProductMapper productMapper;
    private final ProductCategoryMapper categoryMapper;
    private final ProductStoreMapper productStoreMapper;
    /** 复用消费端详情的字段组装与缓存（本 DTO 仅在其上补门店维度）。 */
    private final ProductQueryService productQueryService;

    public StoreProductService(ProductMapper productMapper,
                               ProductCategoryMapper categoryMapper,
                               ProductStoreMapper productStoreMapper,
                               ProductQueryService productQueryService) {
        this.productMapper = productMapper;
        this.categoryMapper = categoryMapper;
        this.productStoreMapper = productStoreMapper;
        this.productQueryService = productQueryService;
    }

    /**
     * 门店选品分页列表。
     *
     * <p>筛选全部下推查询，保证 {@code total} 准确：
     * <ul>
     *   <li>{@code keyword} —— 匹配商品名称或编号；</li>
     *   <li>{@code categoryId} —— 按分类过滤；</li>
     *   <li>{@code listed} —— true=仅已上架 / false=仅已下架 / null=全部。</li>
     * </ul>
     */
    public PageResult<StoreProductDTO> pageStoreProducts(Long storeSubjectId,
                                                         long current,
                                                         long size,
                                                         String keyword,
                                                         Long categoryId,
                                                         Boolean listed) {
        long safeCurrent = current < 1 ? 1 : current;
        long safeSize = size < 1 ? 20 : Math.min(size, MAX_PAGE_SIZE);

        List<Long> listedIds = productStoreMapper.selectListedProductIds(storeSubjectId);
        // 已上架集合；用于 listed 过滤与标记
        List<Long> listedIdList = listedIds == null ? List.of() : listedIds;

        LambdaQueryWrapper<Product> query = new LambdaQueryWrapper<Product>()
                .eq(Product::getOnSale, 1)
                .orderByAsc(Product::getId);

        String trimmed = StringUtils.hasText(keyword) ? keyword.trim() : null;
        if (trimmed != null) {
            query.and(w -> w.like(Product::getName, trimmed).or().like(Product::getProductId, trimmed));
        }
        if (categoryId != null) {
            query.eq(Product::getCategoryId, categoryId);
        }
        // 上架状态过滤下推为 in / not in（数据量可控；避免全表拉取后再过滤导致分页不准）
        if (listed != null) {
            if (listed) {
                if (listedIdList.isEmpty()) {
                    return PageResult.of(List.of(), safeCurrent, safeSize, 0);
                }
                query.in(Product::getId, listedIdList);
            } else {
                if (!listedIdList.isEmpty()) {
                    query.notIn(Product::getId, listedIdList);
                }
            }
        }

        List<Product> all = productMapper.selectList(query);
        long total = all.size();
        long offset = (safeCurrent - 1) * safeSize;
        List<Product> pageProducts = offset >= total
                ? List.of()
                : all.subList((int) offset, (int) Math.min(offset + safeSize, total));

        Map<Long, String> categoryNames = loadCategoryNames(pageProducts);
        List<StoreProductDTO> records = new ArrayList<>();
        for (Product product : pageProducts) {
            records.add(toDTO(product, listedIdList, categoryNames));
        }
        return PageResult.of(records, safeCurrent, safeSize, total);
    }

    /**
     * 门店选品详情（只读）。
     *
     * <p><b>为什么单独一个方法而不复用商品列表</b>：详情页需要商品自身的完整展示字段
     * （规格 / 原料 / 过敏原 / 提示等），列表只返回摘要；而消费端详情接口
     * {@code /api/v1/app/products/{id}} 不携带门店上下架状态，故在服务端补齐门店维度。
     *
     * <p><b>为什么不走缓存</b>：结果含门店维度 {@code listed}，同商品在不同门店取值不同，
     * 套用消费端那份按 productId 的缓存会串数据。商品自身字段仍由
     * {@link ProductQueryService#getProductDetail(String)} 内部走缓存，无额外开销。
     *
     * <p><b>口径一致性</b>：仅返回平台已上架商品（{@code on_sale = 1}），
     * 与列表页 {@link #pageStoreProducts} 相同；平台已下架或不存在返回 {@code null}，
     * 由控制层转 404 语义，避免门店看到不可售商品。
     *
     * @param storeSubjectId 门店主体 ID
     * @param productId      商品业务编号（product.product_id）
     * @return 详情；商品不存在或平台已下架时返回 null
     */
    public StoreProductDetailDTO getStoreProductDetail(Long storeSubjectId, String productId) {
        if (!StringUtils.hasText(productId)) {
            return null;
        }
        Product product = productMapper.selectOne(new LambdaQueryWrapper<Product>()
                .eq(Product::getProductId, productId.trim()));
        if (product == null || !Integer.valueOf(1).equals(product.getOnSale())) {
            return null;
        }

        var base = productQueryService.getProductDetail(productId.trim());
        if (base == null) {
            return null;
        }

        StoreProductDetailDTO dto = new StoreProductDetailDTO();
        org.springframework.beans.BeanUtils.copyProperties(base, dto);

        // 门店维度：product_store 以商品主键关联，故用 product.getId() 而非业务编号
        Long linked = productStoreMapper.selectCount(new LambdaQueryWrapper<ProductStore>()
                .eq(ProductStore::getStoreSubjectId, storeSubjectId)
                .eq(ProductStore::getProductId, product.getId()));
        dto.setPlatformListed(true);
        dto.setListed(linked != null && linked > 0);
        return dto;
    }

    /** 单条上架 / 下架。 */
    @Transactional(rollbackFor = Exception.class)
    public void updateListing(Long storeSubjectId, Long productId, boolean listed) {
        Product product = productMapper.selectById(productId);
        if (product == null || !Integer.valueOf(1).equals(product.getOnSale())) {
            throw new com.wuling.common.exception.BusinessException(
                    com.wuling.common.api.ResultCode.BAD_REQUEST, "商品不存在或平台已下架");
        }
        if (listed) {
            addListing(storeSubjectId, productId);
        } else {
            productStoreMapper.physicalDeleteByStoreAndProduct(storeSubjectId, productId);
        }
    }

    /** 批量上架 / 下架。 */
    @Transactional(rollbackFor = Exception.class)
    public int updateListingBatch(Long storeSubjectId, List<Long> productIds, boolean listed) {
        if (productIds == null || productIds.isEmpty()) {
            return 0;
        }
        int affected = 0;
        for (Long productId : productIds.stream().filter(Objects::nonNull).distinct().toList()) {
            updateListing(storeSubjectId, productId, listed);
            affected++;
        }
        return affected;
    }

    /** 幂等上架：已有记录则跳过，避免唯一键冲突。 */
    private void addListing(Long storeSubjectId, Long productId) {
        Long exists = productStoreMapper.selectCount(new LambdaQueryWrapper<ProductStore>()
                .eq(ProductStore::getStoreSubjectId, storeSubjectId)
                .eq(ProductStore::getProductId, productId));
        if (exists != null && exists > 0) {
            return;
        }
        ProductStore link = new ProductStore();
        link.setProductId(productId);
        link.setStoreSubjectId(storeSubjectId);
        try {
            productStoreMapper.insert(link);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发上架：唯一键拦截，视为成功
        }
    }

    private Map<Long, String> loadCategoryNames(List<Product> products) {
        List<Long> categoryIds = products.stream()
                .map(Product::getCategoryId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> result = new LinkedHashMap<>();
        if (categoryIds.isEmpty()) {
            return result;
        }
        List<ProductCategory> categories = categoryMapper.selectBatchIds(categoryIds);
        if (categories != null) {
            for (ProductCategory category : categories) {
                if (category != null && category.getId() != null) {
                    result.put(category.getId(), category.getName());
                }
            }
        }
        return result;
    }

    private StoreProductDTO toDTO(Product product, List<Long> listedIds, Map<Long, String> categoryNames) {
        StoreProductDTO dto = new StoreProductDTO();
        // 对外展示 ID 优先用业务编号（product_id/code），与点单页一致
        String businessId = StringUtils.hasText(product.getProductId())
                ? product.getProductId()
                : product.getCode();
        dto.setId(businessId);
        dto.setProductId(product.getId());
        dto.setName(product.getName());
        dto.setPrice(product.getPrice());
        dto.setOriginalPrice(product.getOriginalPrice());
        dto.setImage(product.getImage());
        dto.setCategoryId(product.getCategoryId());
        dto.setCategoryLabel(categoryNames.getOrDefault(product.getCategoryId(), "-"));
        dto.setPlatformListed(true);
        dto.setListed(listedIds.contains(product.getId()));
        return dto;
    }
}
