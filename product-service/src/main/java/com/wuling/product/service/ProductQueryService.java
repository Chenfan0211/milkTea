package com.wuling.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.common.api.PageResult;
import com.wuling.common.redis.RedisManager;
import com.wuling.common.redis.RedisNamespace;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;
import com.wuling.product.dto.AdminProductDTO;
import com.wuling.product.dto.CategoryDTO;
import com.wuling.product.dto.MenuDTO;
import com.wuling.product.dto.ProductDetailDTO;
import com.wuling.product.entity.Product;
import com.wuling.product.entity.ProductCategory;
import com.wuling.product.entity.ProductSpec;
import com.wuling.product.entity.ProductStore;
import com.wuling.product.mapper.ProductCategoryMapper;
import com.wuling.product.mapper.ProductMapper;
import com.wuling.product.mapper.ProductSpecMapper;
import com.wuling.product.mapper.ProductStoreMapper;
import com.wuling.product.port.SubjectQueryPort;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ProductQueryService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ProductMapper productMapper;
    private final ProductCategoryMapper categoryMapper;
    private final ProductSpecMapper specMapper;
    private final ProductStoreMapper productStoreMapper;
    /** 第 12 期：主体查询改走端口 */
    private final SubjectQueryPort subjectQueryPort;
    private final ObjectMapper objectMapper;
    private final RedisManager redisManager;

    public ProductQueryService(ProductMapper productMapper,
                               ProductCategoryMapper categoryMapper,
                               ProductSpecMapper specMapper,
                               ProductStoreMapper productStoreMapper,
                               SubjectQueryPort subjectQueryPort,
                               ObjectMapper objectMapper,
                               RedisManager redisManager) {
        this.productMapper = productMapper;
        this.categoryMapper = categoryMapper;
        this.specMapper = specMapper;
        this.productStoreMapper = productStoreMapper;
        this.subjectQueryPort = subjectQueryPort;
        this.objectMapper = objectMapper;
        this.redisManager = redisManager;
    }

    /** 菜单缓存 key（CACHE 命名空间，TTL 5 分钟） */
    private static final String MENU_CACHE_KEY = "menu:all";
    private static final Duration MENU_CACHE_TTL = Duration.ofMinutes(5);

    /** 商品详情缓存 key 前缀（CACHE 命名空间，TTL 5 分钟） */
    private static final String PRODUCT_CACHE_PREFIX = "product:detail:";
    private static final Duration PRODUCT_CACHE_TTL = Duration.ofMinutes(5);

    public List<MenuDTO.MenuTab> getMenu() {
        // 缓存优先：菜单数据读多写少，命中后跳过 3 次全表查询 + 43KB 组装
        String cached = safeGetMenuCache();
        if (cached != null) {
            List<MenuDTO.MenuTab> hit = parseMenuCache(cached);
            if (hit != null) {
                return hit;
            }
        }

        // 单层分类：仅启用且 type=CATEGORY 的节点参与菜单
        List<ProductCategory> categories = categoryMapper.selectList(new LambdaQueryWrapper<ProductCategory>()
                .eq(ProductCategory::getType, "CATEGORY")
                .eq(ProductCategory::getEnabled, 1)
                .orderByAsc(ProductCategory::getSort)
                .orderByAsc(ProductCategory::getId));
        List<Product> products = productMapper.selectList(new LambdaQueryWrapper<Product>()
                .eq(Product::getOnSale, 1)
                .orderByAsc(Product::getId));
        List<ProductSpec> specs = specMapper.selectList(new LambdaQueryWrapper<ProductSpec>()
                .orderByAsc(ProductSpec::getSort)
                .orderByAsc(ProductSpec::getId));

        Map<Long, List<ProductSpec>> specsByProduct = specs.stream()
                .collect(Collectors.groupingBy(ProductSpec::getProductId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, List<Product>> productsByCategory = products.stream()
                .collect(Collectors.groupingBy(Product::getCategoryId, LinkedHashMap::new, Collectors.toList()));

        // 完全单层：1 个 tab -> 1 个 group -> 分类列表
        MenuDTO.MenuTab tabDto = new MenuDTO.MenuTab();
        tabDto.setId("menu");
        tabDto.setLabel("菜单");

        MenuDTO.MenuGroup groupDto = new MenuDTO.MenuGroup();
        groupDto.setId("all");
        groupDto.setLabel("全部");

        List<MenuDTO.MenuCategory> categoryDtos = new ArrayList<>();
        for (ProductCategory category : categories) {
            MenuDTO.MenuCategory categoryDto = new MenuDTO.MenuCategory();
            categoryDto.setId(category.getCode());
            categoryDto.setLabel(category.getName());
            categoryDto.setTag(category.getTag());
            categoryDto.setProducts(productsByCategory.getOrDefault(category.getId(), List.of()).stream()
                    .map(p -> toMenuProduct(p, specsByProduct.getOrDefault(p.getId(), List.of())))
                    .toList());
            categoryDtos.add(categoryDto);
        }
        groupDto.setCategories(categoryDtos);

        List<MenuDTO.MenuGroup> groups = new ArrayList<>();
        groups.add(groupDto);
        tabDto.setGroups(groups);

        List<MenuDTO.MenuTab> tabs = new ArrayList<>();
        tabs.add(tabDto);

        // 写回缓存（序列化失败不影响返回）
        safeSetMenuCache(tabs);
        return tabs;
    }

    /** 读菜单缓存；Redis 不可用或未命中返回 null（fail-open，回源 DB）。 */
    private String safeGetMenuCache() {
        try {
            StringRedisTemplate redis = redisManager.template(RedisNamespace.CACHE);
            return redis.opsForValue().get(redisManager.key(RedisNamespace.CACHE, MENU_CACHE_KEY));
        } catch (Exception e) {
            return null;
        }
    }

    /** 写菜单缓存；序列化或 Redis 失败时静默忽略。 */
    private void safeSetMenuCache(List<MenuDTO.MenuTab> tabs) {
        try {
            String json = objectMapper.writeValueAsString(tabs);
            StringRedisTemplate redis = redisManager.template(RedisNamespace.CACHE);
            redis.opsForValue().set(redisManager.key(RedisNamespace.CACHE, MENU_CACHE_KEY), json, MENU_CACHE_TTL);
        } catch (Exception e) {
            // 缓存写入失败不影响业务
        }
    }

    /** 反序列化菜单缓存；损坏返回 null（走回源）。 */
    private List<MenuDTO.MenuTab> parseMenuCache(String json) {
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<MenuDTO.MenuTab>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    /** 菜单数据变更后失效缓存（供商品/分类/规格的增删改调用）。 */
    public void evictMenuCache() {
        try {
            StringRedisTemplate redis = redisManager.template(RedisNamespace.CACHE);
            redis.delete(redisManager.key(RedisNamespace.CACHE, MENU_CACHE_KEY));
        } catch (Exception e) {
            // 删除失败可忽略，缓存 TTL 兜底
        }
    }

    private String safeGetProductCache(String productId) {
        try {
            StringRedisTemplate redis = redisManager.template(RedisNamespace.CACHE);
            return redis.opsForValue().get(redisManager.key(RedisNamespace.CACHE, PRODUCT_CACHE_PREFIX + productId));
        } catch (Exception e) {
            return null;
        }
    }

    private void safeSetProductCache(String productId, ProductDetailDTO dto) {
        try {
            String json = objectMapper.writeValueAsString(dto);
            StringRedisTemplate redis = redisManager.template(RedisNamespace.CACHE);
            redis.opsForValue().set(redisManager.key(RedisNamespace.CACHE, PRODUCT_CACHE_PREFIX + productId), json, PRODUCT_CACHE_TTL);
        } catch (Exception e) {
            // 缓存写入失败不影响业务
        }
    }

    private ProductDetailDTO parseProductCache(String json) {
        try {
            return objectMapper.readValue(json, ProductDetailDTO.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 商品详情变更后失效单个商品缓存（供商品写操作调用）。 */
    public void evictProductCache(String productId) {
        try {
            StringRedisTemplate redis = redisManager.template(RedisNamespace.CACHE);
            redis.delete(redisManager.key(RedisNamespace.CACHE, PRODUCT_CACHE_PREFIX + productId));
        } catch (Exception e) {
            // 删除失败可忽略，缓存 TTL 兜底
        }
    }
    public ProductDetailDTO getProductDetail(String productId) {
        // 缓存优先：商品详情读多写少，命中后跳过 2 次 DB 查询
        if (StringUtils.hasText(productId)) {
            String cached = safeGetProductCache(productId);
            if (cached != null) {
                ProductDetailDTO hit = parseProductCache(cached);
                if (hit != null) {
                    return hit;
                }
            }
        }

        Product product = productMapper.selectOne(new LambdaQueryWrapper<Product>()
                .eq(Product::getProductId, productId));
        if (product == null) {
            return null;
        }
        List<ProductSpec> specs = specMapper.selectList(new LambdaQueryWrapper<ProductSpec>()
                .eq(ProductSpec::getProductId, product.getId())
                .orderByAsc(ProductSpec::getSort)
                .orderByAsc(ProductSpec::getId));
        ProductDetailDTO dto = new ProductDetailDTO();
        dto.setId(product.getProductId());
        dto.setName(product.getName());
        dto.setTags(parseStringList(product.getTags()));
        dto.setDescription(product.getDescription());
        dto.setPrice(product.getPrice());
        dto.setOriginalPrice(product.getOriginalPrice());
        dto.setStoredValuePrice(product.getStoredValuePrice());
        dto.setImage(product.getImage());
        dto.setGalleryImage(product.getGalleryImage() == null ? product.getImage() : product.getGalleryImage());
        dto.setImageDisclaimer(product.getImageDisclaimer());
        dto.setPromotionText(product.getPromotionText());
        dto.setPriceLabel(product.getPriceLabel());
        dto.setDiscountRate(product.getDiscountRate());
        dto.setSpecTag(product.getSpecTag());
        dto.setBadgeIcon(product.getBadgeIcon());
        dto.setIngredients(product.getIngredients());
        dto.setAllergens(product.getAllergens());
        dto.setCupCapacity(product.getCupCapacity());
        dto.setTips(parseStringList(product.getTips()));
        dto.setSpecGroups(buildSpecGroups(specs));

        // 写回缓存（序列化失败不影响返回）
        safeSetProductCache(productId, dto);
        return dto;
    }

    public PageResult<AdminProductDTO> pageAdminProducts(long current, long size, String search) {
        LambdaQueryWrapper<Product> query = new LambdaQueryWrapper<Product>().orderByAsc(Product::getId);
        if (StringUtils.hasText(search)) {
            query.and(w -> w.like(Product::getProductId, search)
                    .or().like(Product::getName, search)
                    .or().like(Product::getCode, search));
        }
        Page<Product> page = productMapper.selectPage(new Page<>(current, size), query);
        List<Product> records = page.getRecords();

        // 批量预加载，避免 N+1：分类 / 规格 / 门店 / 门店名各查一次
        Map<Long, ProductCategory> categoryMap = loadCategoryMap(records);
        Map<Long, List<ProductSpec>> specsMap = loadSpecsMap(records);
        Map<Long, List<String>> storeNamesMap = loadStoreNamesMap(records);

        List<AdminProductDTO> dtos = records.stream()
                .map(p -> toAdminProductBatch(p, categoryMap, specsMap, storeNamesMap))
                .toList();
        return PageResult.of(dtos, page.getCurrent(), page.getSize(), page.getTotal());
    }

    /** 批量加载分类映射（productId -> 分类） */
    private Map<Long, ProductCategory> loadCategoryMap(List<Product> records) {
        List<Long> ids = records.stream().map(Product::getCategoryId).filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        return categoryMapper.selectList(new LambdaQueryWrapper<ProductCategory>().in(ProductCategory::getId, ids))
                .stream().collect(Collectors.toMap(ProductCategory::getId, c -> c));
    }

    /** 批量加载规格映射（productId -> 规格列表） */
    private Map<Long, List<ProductSpec>> loadSpecsMap(List<Product> records) {
        List<Long> ids = records.stream().map(Product::getId).toList();
        if (ids.isEmpty()) return Map.of();
        return specMapper.selectList(new LambdaQueryWrapper<ProductSpec>().in(ProductSpec::getProductId, ids))
                .stream().collect(Collectors.groupingBy(ProductSpec::getProductId));
    }

    /** 批量加载门店名映射（productId -> 门店名列表），门店名经 subjectQueryPort.findNames 批量查询 */
    private Map<Long, List<String>> loadStoreNamesMap(List<Product> records) {
        List<Long> ids = records.stream().map(Product::getId).toList();
        if (ids.isEmpty()) return Map.of();
        List<ProductStore> links = productStoreMapper.selectList(new LambdaQueryWrapper<ProductStore>().in(ProductStore::getProductId, ids));
        if (links.isEmpty()) return Map.of();
        // 批量查门店主体名
        List<Long> subjectIds = links.stream().map(ProductStore::getStoreSubjectId).distinct().toList();
        Map<Long, String> subjectNames = subjectQueryPort.findNames(subjectIds);
        Map<Long, List<String>> result = new java.util.HashMap<>();
        for (ProductStore link : links) {
            String name = subjectNames.get(link.getStoreSubjectId());
            if (name != null) {
                result.computeIfAbsent(link.getProductId(), k -> new ArrayList<>()).add(name);
            }
        }
        return result;
    }

    /** 用预加载的映射批量组装单个商品 DTO（避免 N+1） */
    private AdminProductDTO toAdminProductBatch(Product product,
                                                Map<Long, ProductCategory> categoryMap,
                                                Map<Long, List<ProductSpec>> specsMap,
                                                Map<Long, List<String>> storeNamesMap) {
        AdminProductDTO dto = toAdminProductBase(product);
        // 分类
        ProductCategory category = product.getCategoryId() == null ? null : categoryMap.get(product.getCategoryId());
        dto.setCategory(category == null ? "-" : category.getName());
        dto.setCategoryId(product.getCategoryId());
        // 规格
        List<ProductSpec> specs = specsMap.getOrDefault(product.getId(), List.of());
        dto.setSpecCount((int) specs.stream().map(ProductSpec::getGroupCode).distinct().count());
        // 门店名
        List<String> storeNames = storeNamesMap.getOrDefault(product.getId(), List.of());
        dto.setStores(storeNames);
        dto.setStore(storeNames.isEmpty() ? "-" : storeNames.get(0));
        return dto;
    }

    /** 按 id 组装单个商品 DTO（写操作后回填用） */
    public AdminProductDTO getAdminProduct(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            return null;
        }
        return toAdminProduct(product);
    }

    public List<CategoryDTO> listCategories() {
        return categoryMapper.selectList(new LambdaQueryWrapper<ProductCategory>()
                        .eq(ProductCategory::getType, "CATEGORY")
                        .orderByAsc(ProductCategory::getSort)
                        .orderByAsc(ProductCategory::getId))
                .stream().map(category -> {
                    CategoryDTO dto = new CategoryDTO();
                    dto.setId(category.getId());
                    dto.setParentId(category.getParentId());
                    dto.setCode(category.getCode());
                    dto.setName(category.getName());
                    dto.setType(category.getType());
                    dto.setSort(category.getSort());
                    dto.setTag(category.getTag());
                    dto.setEnabled(category.getEnabled());
                    return dto;
                }).toList();
    }
    private MenuDTO.MenuProduct toMenuProduct(Product product, List<ProductSpec> specs) {
        MenuDTO.MenuProduct dto = new MenuDTO.MenuProduct();
        dto.setId(product.getProductId());
        dto.setName(product.getName());
        dto.setTags(parseStringList(product.getTags()));
        dto.setDescription(product.getDescription());
        dto.setPrice(product.getPrice());
        dto.setOriginalPrice(product.getOriginalPrice());
        dto.setStoredValuePrice(product.getStoredValuePrice());
        dto.setImage(product.getImage());
        dto.setGalleryImage(product.getGalleryImage() == null ? product.getImage() : product.getGalleryImage());
        dto.setImageDisclaimer(product.getImageDisclaimer());
        dto.setPromotionText(product.getPromotionText());
        dto.setPriceLabel(product.getPriceLabel());
        dto.setDiscountRate(product.getDiscountRate());
        dto.setSpecTag(product.getSpecTag());
        dto.setBadgeIcon(product.getBadgeIcon());
        dto.setIngredients(product.getIngredients());
        dto.setAllergens(product.getAllergens());
        dto.setCupCapacity(product.getCupCapacity());
        dto.setTips(parseStringList(product.getTips()));
        dto.setSpecGroups(buildSpecGroups(specs));
        return dto;
    }

    private List<MenuDTO.SpecGroup> buildSpecGroups(List<ProductSpec> specs) {
        Map<String, List<ProductSpec>> byGroup = specs.stream()
                .collect(Collectors.groupingBy(ProductSpec::getGroupCode, LinkedHashMap::new, Collectors.toList()));
        List<MenuDTO.SpecGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<ProductSpec>> entry : byGroup.entrySet()) {
            MenuDTO.SpecGroup group = new MenuDTO.SpecGroup();
            group.setId(entry.getKey());
            group.setLabel(entry.getValue().isEmpty() ? entry.getKey() : entry.getValue().get(0).getGroupLabel());
            List<MenuDTO.SpecOption> options = entry.getValue().stream()
                    .sorted(Comparator.comparing(ProductSpec::getSort, Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(spec -> {
                        MenuDTO.SpecOption option = new MenuDTO.SpecOption();
                        option.setId(spec.getOptionCode());
                        option.setLabel(spec.getOptionLabel());
                        option.setSelected(spec.getSelected() != null && spec.getSelected() == 1);
                        option.setPriceDelta(spec.getPriceDelta());
                        option.setIcon(spec.getIcon());
                        return option;
                    }).toList();
            group.setOptions(options);
            groups.add(group);
        }
        return groups;
    }

    /** 组装商品 DTO 的基础字段（不含关联查询），供批量与单个共用。 */
    private AdminProductDTO toAdminProductBase(Product product) {
        AdminProductDTO dto = new AdminProductDTO();
        dto.setId(product.getId());
        dto.setProductId(product.getProductId());
        dto.setCode(product.getCode());
        dto.setName(product.getName());
        dto.setPrice(product.getPrice());
        dto.setOriginalPrice(product.getOriginalPrice());
        dto.setCostPrice(product.getCostPrice());
        dto.setPlatformCommission(product.getPlatformCommission());
        dto.setStoredValuePrice(product.getStoredValuePrice());
        dto.setGalleryImage(product.getGalleryImage() == null ? product.getImage() : product.getGalleryImage());
        dto.setImageDisclaimer(product.getImageDisclaimer());
        dto.setPromotionText(product.getPromotionText());
        dto.setIngredients(product.getIngredients());
        dto.setAllergens(product.getAllergens());
        dto.setCupCapacity(product.getCupCapacity());
        dto.setTips(parseStringList(product.getTips()));
        dto.setDescription(product.getDescription());
        dto.setOnSale(product.getOnSale() != null && product.getOnSale() == 1 ? "on" : "off");
        dto.setSplitReady(product.getSplitRuleId() != null ? "ready" : "incomplete");
        dto.setCreateTime(product.getCreateTime() == null ? null : product.getCreateTime().format(FMT));
        dto.setTags(parseStringList(product.getTags()));
        return dto;
    }

    /** 单个商品 DTO（写操作后回填用），关联查询逐个进行（低频场景）。 */
    private AdminProductDTO toAdminProduct(Product product) {
        AdminProductDTO dto = toAdminProductBase(product);
        // 分类
        ProductCategory category = product.getCategoryId() == null ? null : categoryMapper.selectById(product.getCategoryId());
        dto.setCategory(category == null ? "-" : category.getName());
        dto.setCategoryId(product.getCategoryId());
        // 规格
        List<ProductSpec> specs = specMapper.selectList(new LambdaQueryWrapper<ProductSpec>()
                .eq(ProductSpec::getProductId, product.getId()));
        dto.setSpecCount((int) specs.stream().map(ProductSpec::getGroupCode).distinct().count());
        // 门店名
        List<String> storeNames = new ArrayList<>();
        List<ProductStore> links = productStoreMapper.selectList(new LambdaQueryWrapper<ProductStore>()
                .eq(ProductStore::getProductId, product.getId()));
        for (ProductStore link : links) {
            String storeName = subjectQueryPort.findName(link.getStoreSubjectId());
            if (storeName != null) {
                storeNames.add(storeName);
            }
        }
        dto.setStores(storeNames);
        dto.setStore(storeNames.isEmpty() ? "-" : storeNames.get(0));
        return dto;
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

