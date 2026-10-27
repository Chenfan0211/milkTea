package com.wuling.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.common.api.PageResult;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.product.dto.MallProductCardDTO;
import com.wuling.product.dto.MallProductDetailDTO;
import com.wuling.product.dto.MallSkuInternalView;
import com.wuling.product.dto.MallSkuValidateRequest;
import com.wuling.product.dto.MallSkuValidateResponse;
import com.wuling.product.dto.MallSkuView;
import com.wuling.product.entity.MallCategory;
import com.wuling.product.entity.MallProduct;
import com.wuling.product.entity.MallSku;
import com.wuling.product.mapper.MallCategoryMapper;
import com.wuling.product.mapper.MallProductMapper;
import com.wuling.product.mapper.MallSkuMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class MallProductQueryService {

    private static final long MAX_PAGE_SIZE = 100;
    private static final String AVAILABLE_SKU_EXISTS =
            "select 1 from mall_sku s where s.product_id = mall_product.id "
                    + "and s.deleted = 0 and s.stock > s.locked_stock";

    private final MallProductMapper productMapper;
    private final MallCategoryMapper categoryMapper;
    private final MallSkuMapper skuMapper;
    private final ObjectMapper objectMapper;

    public MallProductQueryService(MallProductMapper productMapper,
                                   MallCategoryMapper categoryMapper,
                                   MallSkuMapper skuMapper,
                                   ObjectMapper objectMapper) {
        this.productMapper = productMapper;
        this.categoryMapper = categoryMapper;
        this.skuMapper = skuMapper;
        this.objectMapper = objectMapper;
    }

    public PageResult<MallProductCardDTO> pageAppProducts(Long categoryId, long page, long size) {
        long safePage = page < 1 ? 1 : page;
        long safeSize = size < 1 ? 20 : Math.min(size, MAX_PAGE_SIZE);
        QueryWrapper<MallProduct> query = new QueryWrapper<MallProduct>()
                .eq("status", "ON_SALE")
                .eq(categoryId != null, "category_id", categoryId)
                // 售罄商品保留展示并沉底；随后按运营排序、ID 倒序形成稳定分页顺序。
                .orderByAsc("case when exists(" + AVAILABLE_SKU_EXISTS + ") then 0 else 1 end")
                .orderByAsc("sort")
                .orderByDesc("id");
        Page<MallProduct> result = productMapper.selectPage(new Page<>(safePage, safeSize), query);
        Map<Long, List<MallSku>> skusByProduct = loadSkusByProduct(result.getRecords());
        List<MallProductCardDTO> records = result.getRecords().stream()
                .map(product -> toCard(product, skusByProduct.getOrDefault(product.getId(), List.of())))
                .toList();
        return PageResult.of(records, result.getCurrent(), result.getSize(), result.getTotal());
    }

    public MallProductDetailDTO getAppProduct(Long id) {
        MallProduct product = productMapper.selectOne(new LambdaQueryWrapper<MallProduct>()
                .eq(MallProduct::getId, id)
                .eq(MallProduct::getStatus, "ON_SALE"));
        if (product == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "商城商品不存在或已下架");
        }
        List<MallSku> skus = skuMapper.selectList(new LambdaQueryWrapper<MallSku>()
                .eq(MallSku::getProductId, id)
                .orderByAsc(MallSku::getSort)
                .orderByAsc(MallSku::getId));
        MallCategory category = product.getCategoryId() == null ? null : categoryMapper.selectById(product.getCategoryId());
        MallProductDetailDTO dto = new MallProductDetailDTO();
        dto.setId(product.getId());
        dto.setProductCode(product.getProductCode());
        dto.setCategoryId(product.getCategoryId());
        dto.setCategoryName(category == null ? null : category.getName());
        dto.setName(product.getName());
        dto.setSubtitle(product.getSubtitle());
        dto.setSpecName(product.getSpecName());
        dto.setCoverImage(product.getCoverImage());
        dto.setGalleryImages(parseStringList(product.getGalleryImages()));
        dto.setDescription(product.getDescription());
        dto.setSalesCount(product.getSalesCount());
        dto.setSkus(skus.stream().map(this::toSkuView).toList());
        dto.setMinPrice(min(skus, MallSku::getSalePrice));
        dto.setOriginalPrice(min(skus, MallSku::getOriginalPrice));
        dto.setAvailableStock(totalAvailable(skus));
        dto.setSoldOut(dto.getAvailableStock() <= 0);
        return dto;
    }

    public MallSkuValidateResponse validateSkus(MallSkuValidateRequest request) {
        List<MallSkuValidateRequest.Item> requestItems = request == null || request.getItems() == null
                ? List.of() : request.getItems();
        Set<Long> skuIds = requestItems.stream()
                .map(MallSkuValidateRequest.Item::getSkuId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, MallSku> skuMap = skuIds.isEmpty() ? Map.of()
                : skuMapper.selectBatchIds(skuIds).stream()
                .collect(Collectors.toMap(MallSku::getId, Function.identity()));
        Set<Long> productIds = skuMap.values().stream().map(MallSku::getProductId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, MallProduct> productMap = productIds.isEmpty() ? Map.of()
                : productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(MallProduct::getId, Function.identity()));

        List<MallSkuValidateResponse.Item> results = new ArrayList<>();
        for (MallSkuValidateRequest.Item requestItem : requestItems) {
            MallSkuValidateResponse.Item item = new MallSkuValidateResponse.Item();
            item.setSkuId(requestItem.getSkuId());
            item.setQuantity(requestItem.getQuantity());
            MallSku sku = skuMap.get(requestItem.getSkuId());
            if (sku == null) {
                item.setValid(false);
                item.setReason("SKU 不存在");
                results.add(item);
                continue;
            }
            MallProduct product = productMap.get(sku.getProductId());
            fillSku(item, sku, product);
            if (product == null || !"ON_SALE".equals(product.getStatus())) {
                item.setValid(false);
                item.setReason("商品已下架");
            } else if (requestItem.getQuantity() == null || requestItem.getQuantity() < 1) {
                item.setValid(false);
                item.setReason("购买数量必须大于0");
            } else if (sku.availableStock() < requestItem.getQuantity()) {
                item.setValid(false);
                item.setReason("可用库存不足");
            } else {
                item.setValid(true);
            }
            results.add(item);
        }
        MallSkuValidateResponse response = new MallSkuValidateResponse();
        response.setItems(results);
        return response;
    }

    public List<MallSkuInternalView> queryInternalSkus(List<Long> skuIds) {
        List<Long> ids = skuIds == null ? List.of() : skuIds.stream()
                .filter(Objects::nonNull).distinct().sorted().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<MallSku> skus = skuMapper.selectBatchIds(ids);
        if (skus == null || skus.isEmpty()) {
            return List.of();
        }
        Set<Long> productIds = skus.stream().map(MallSku::getProductId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, MallProduct> products = productIds.isEmpty() ? Map.of()
                : productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(MallProduct::getId, Function.identity()));
        return skus.stream()
                .sorted(Comparator.comparing(MallSku::getId))
                .map(sku -> toInternalView(sku, products.get(sku.getProductId())))
                .toList();
    }

    private Map<Long, List<MallSku>> loadSkusByProduct(List<MallProduct> products) {
        List<Long> productIds = products.stream().map(MallProduct::getId).toList();
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return skuMapper.selectList(new LambdaQueryWrapper<MallSku>()
                        .in(MallSku::getProductId, productIds)
                        .orderByAsc(MallSku::getSort)
                        .orderByAsc(MallSku::getId))
                .stream().collect(Collectors.groupingBy(MallSku::getProductId));
    }

    private MallProductCardDTO toCard(MallProduct product, List<MallSku> skus) {
        MallProductCardDTO dto = new MallProductCardDTO();
        dto.setId(product.getId());
        dto.setProductCode(product.getProductCode());
        dto.setCategoryId(product.getCategoryId());
        dto.setName(product.getName());
        dto.setSubtitle(product.getSubtitle());
        dto.setCoverImage(product.getCoverImage());
        dto.setSort(product.getSort());
        dto.setMinPrice(min(skus, MallSku::getSalePrice));
        dto.setOriginalPrice(min(skus, MallSku::getOriginalPrice));
        dto.setAvailableStock(totalAvailable(skus));
        dto.setSoldOut(dto.getAvailableStock() <= 0);
        return dto;
    }

    private MallSkuView toSkuView(MallSku sku) {
        MallSkuView view = new MallSkuView();
        view.setId(sku.getId());
        view.setSkuCode(sku.getSkuCode());
        view.setSpecValue(sku.getSpecValue());
        view.setImage(sku.getImage());
        view.setSalePrice(sku.getSalePrice());
        view.setOriginalPrice(sku.getOriginalPrice());
        view.setAvailableStock(sku.availableStock());
        view.setSoldOut(sku.availableStock() <= 0);
        return view;
    }

    private MallSkuInternalView toInternalView(MallSku sku, MallProduct product) {
        MallSkuInternalView view = new MallSkuInternalView();
        view.setSkuId(sku.getId());
        view.setProductId(sku.getProductId());
        view.setSkuCode(sku.getSkuCode());
        view.setSpecValue(sku.getSpecValue());
        view.setImage(sku.getImage());
        view.setSalePrice(sku.getSalePrice());
        view.setOriginalPrice(sku.getOriginalPrice());
        view.setCostPrice(sku.getCostPrice());
        view.setPlatformCommission(sku.getPlatformCommission());
        view.setSupplierSubjectId(sku.getSupplierSubjectId());
        view.setStock(sku.getStock());
        view.setLockedStock(sku.getLockedStock());
        view.setAvailableStock(sku.availableStock());
        if (product != null) {
            view.setProductCode(product.getProductCode());
            view.setProductName(product.getName());
            view.setAttributionStoreSubjectId(product.getAttributionStoreSubjectId());
            view.setAttributionChannelSubjectId(product.getAttributionChannelSubjectId());
            view.setSplitRuleId(product.getSplitRuleId());
            view.setOnSale("ON_SALE".equals(product.getStatus()));
        } else {
            view.setOnSale(false);
        }
        return view;
    }

    private void fillSku(MallSkuValidateResponse.Item item, MallSku sku, MallProduct product) {
        item.setProductId(sku.getProductId());
        item.setProductName(product == null ? null : product.getName());
        item.setSkuCode(sku.getSkuCode());
        item.setSpecValue(sku.getSpecValue());
        item.setImage(sku.getImage());
        item.setSalePrice(sku.getSalePrice());
        item.setDistributionCommission(sku.getDistributionCommission());
        item.setOriginalPrice(sku.getOriginalPrice());
        item.setAvailableStock(sku.availableStock());
    }

    private static Integer totalAvailable(List<MallSku> skus) {
        return skus.stream().mapToInt(MallSku::availableStock).sum();
    }

    private static Long min(List<MallSku> skus, Function<MallSku, Long> getter) {
        return skus.stream().map(getter).filter(Objects::nonNull).min(Long::compareTo).orElse(null);
    }

    private List<String> parseStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}


