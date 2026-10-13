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
import java.util.Set;
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

    /**
     * 全量菜单模板缓存 key（CACHE 命名空间，TTL 5 分钟）。
     *
     * <p>不含门店维度的「平台全量菜单」：仅用于不传 storeSubjectId 的旧调用，
     * 以及作为按门店构建时的组装源。门店维度缓存见 {@link #STORE_MENU_CACHE_PREFIX}。
     */
    private static final String MENU_CACHE_KEY = "menu:all";
    private static final Duration MENU_CACHE_TTL = Duration.ofMinutes(5);

    /**
     * 门店菜单缓存 key 前缀（CACHE 命名空间，TTL 5 分钟）。
     *
     * <p><b>为什么按门店分别缓存</b>：不同门店选品不同，若共用一个 key（历史上的
     * {@code menu:all}），要么被迫返回全量、要么每次请求都做内存过滤。按门店缓存
     * 「已过滤结果」后，命中即返回，避免「缓存命中绕过过滤器」这类问题。
     * 完整 key：{@code wuling:cache:menu:store:{storeSubjectId}}。
     */
    private static final String STORE_MENU_CACHE_PREFIX = "menu:store:";

    /** 商品详情缓存 key 前缀（CACHE 命名空间，TTL 5 分钟） */
    private static final String PRODUCT_CACHE_PREFIX = "product:detail:";
    private static final Duration PRODUCT_CACHE_TTL = Duration.ofMinutes(5);

    /** 门店选品上架关系缓存 key 前缀（CACHE 命名空间，TTL 60 秒）。
     * 上架关系变更频繁且需跨门店隔离，TTL 短一些，下架/上架后也会主动失效。 */
    private static final String STORE_LISTING_CACHE_PREFIX = "product:store-listing:";
    private static final Duration STORE_LISTING_CACHE_TTL = Duration.ofSeconds(60);

    /** 全量菜单（不按门店过滤）；兼容旧调用。 */
    public List<MenuDTO.MenuTab> getMenu() {
        return getMenu(null);
    }

    /**
     * 菜单（按门店选品过滤）。
     *
     * <p>分层缓存：
     * <ul>
     *   <li>{@code menu:all}：平台全量菜单模板，供不传门店的旧调用；</li>
     *   <li>{@code menu:store:{id}}：某门店「已过滤」后的菜单，命中即返回。</li>
     * </ul>
     * 门店未上架的商品从各分类中剔除；分类被清空时整组隐藏，避免点单页出现空分类；
     * 门店若未配置任何选品，返回空菜单（不再回落平台全量）。
     */
    public List<MenuDTO.MenuTab> getMenu(Long storeSubjectId) {
        // 未选门店（旧调用）：走「平台全量菜单」缓存，语义与历史一致
        if (storeSubjectId == null) {
            return getPlatformMenu();
        }

        // 门店维度：缓存「已按本店选品过滤后的结果」。
        // 历史 bug：共用一个 menu:all 缓存且命中即返回，门店过滤只在缓存未命中时执行，
        // 导致热缓存下所有门店（含未配选品的测试门店）都看到平台全量菜单。
        String cacheKey = redisManager.key(RedisNamespace.CACHE, STORE_MENU_CACHE_PREFIX + storeSubjectId);
        String cached = safeGet(cacheKey);
        if (cached != null) {
            List<MenuDTO.MenuTab> hit = parseMenuCache(cached);
            if (hit != null) {
                return hit;
            }
        }

        // 未命中：以全量模板为基础做门店过滤，再按门店缓存
        List<MenuDTO.MenuTab> filtered = filterMenuByStore(getPlatformMenu(), storeSubjectId);
        safeSet(cacheKey, filtered, MENU_CACHE_TTL);
        return filtered;
    }

    /**
     * 平台全量菜单（不按门店过滤），走独立缓存 {@code menu:all}。
     * 未选门店的旧调用与门店菜单组装的共同基础。
     */
    private List<MenuDTO.MenuTab> getPlatformMenu() {
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

    /**
     * 按门店选品过滤菜单：剔除「本店未上架」的商品。
     *
     * <p>{@code product_store} 有记录 = 本店已上架；无记录 = 本店未上架。
     * 为避免与其它门店共用缓存产生串数据，过滤只作用于返回给该门店的副本。
     * storeSubjectId 为空时原样返回（未选门店 / 旧调用）。
     */
    private List<MenuDTO.MenuTab> filterMenuByStore(List<MenuDTO.MenuTab> tabs, Long storeSubjectId) {
        if (storeSubjectId == null || tabs == null || tabs.isEmpty()) {
            return tabs;
        }
        java.util.Set<String> listedBusinessIds = getListedBusinessIds(storeSubjectId);
        List<MenuDTO.MenuTab> result = new ArrayList<>();
        for (MenuDTO.MenuTab tab : tabs) {
            List<MenuDTO.MenuGroup> groups = new ArrayList<>();
            for (MenuDTO.MenuGroup group : tab.getGroups() == null ? List.<MenuDTO.MenuGroup>of() : tab.getGroups()) {
                List<MenuDTO.MenuCategory> cats = new ArrayList<>();
                for (MenuDTO.MenuCategory category : group.getCategories() == null
                        ? List.<MenuDTO.MenuCategory>of() : group.getCategories()) {
                    List<MenuDTO.MenuProduct> visible = (category.getProducts() == null
                            ? List.<MenuDTO.MenuProduct>of() : category.getProducts()).stream()
                            .filter(p -> p != null && listedBusinessIds.contains(p.getId()))
                            .toList();
                    if (visible.isEmpty()) {
                        continue;
                    }
                    MenuDTO.MenuCategory copy = new MenuDTO.MenuCategory();
                    copy.setId(category.getId());
                    copy.setLabel(category.getLabel());
                    copy.setTag(category.getTag());
                    copy.setProducts(visible);
                    cats.add(copy);
                }
                if (cats.isEmpty()) {
                    continue;
                }
                MenuDTO.MenuGroup g = new MenuDTO.MenuGroup();
                g.setId(group.getId());
                g.setLabel(group.getLabel());
                g.setCategories(cats);
                groups.add(g);
            }
            if (groups.isEmpty()) {
                continue;
            }
            MenuDTO.MenuTab copyTab = new MenuDTO.MenuTab();
            copyTab.setId(tab.getId());
            copyTab.setLabel(tab.getLabel());
            copyTab.setGroups(groups);
            result.add(copyTab);
        }
        return result;
    }

    /**
     * 某门店「已上架商品」的业务标识集合（productId 与 code 都算，供菜单过滤）。
     *
     * <p>为什么加缓存：旧实现每次菜单过滤都执行 1 次 product_store 查询 + 1 次
     * product 批量查询，且 product_store 因缺 store_subject_id 前缀索引会全表扫描；
     * 加上菜单接口本就高频，这 2 次 DB 查询被放大。这里缓存「上架关系 -> 业务标识」，
     * TTL 60 秒，下架/上架时主动失效，保证点单页选品下架近实时生效。
     */
    public java.util.Set<String> getListedBusinessIds(Long storeSubjectId) {
        if (storeSubjectId == null) {
            return java.util.Set.of();
        }
        String cacheKey = redisManager.key(RedisNamespace.CACHE, STORE_LISTING_CACHE_PREFIX + storeSubjectId);
        StringRedisTemplate redis = redisManager.template(RedisNamespace.CACHE);

        // 1) 读缓存
        try {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null) {
                java.util.Set<String> hit = parseStringSet(cached);
                if (hit != null) {
                    return hit;
                }
            }
        } catch (Exception e) {
            // 缓存不可用回源 DB，不影响业务
        }

        // 2) 回源 DB
        List<Long> listedIds = productStoreMapper.selectListedProductIds(storeSubjectId);
        java.util.Set<String> businessIds = new java.util.HashSet<>();
        if (listedIds != null && !listedIds.isEmpty()) {
            List<Product> listedProducts = productMapper.selectBatchIds(listedIds);
            if (listedProducts != null) {
                for (Product p : listedProducts) {
                    if (p == null) {
                        continue;
                    }
                    if (StringUtils.hasText(p.getProductId())) {
                        businessIds.add(p.getProductId());
                    }
                    if (StringUtils.hasText(p.getCode())) {
                        businessIds.add(p.getCode());
                    }
                }
            }
        }

        // 3) 写回缓存（序列化失败不影响返回）
        try {
            redis.opsForValue().set(cacheKey, objectMapper.writeValueAsString(businessIds), STORE_LISTING_CACHE_TTL);
        } catch (Exception e) {
            // 缓存写入失败不影响业务
        }
        return businessIds;
    }

    /** 门店选品关系变更后失效上架关系缓存（供上架/下架写操作调用）。 */
    public void evictStoreListingCache(Long storeSubjectId) {
        if (storeSubjectId == null) {
            return;
        }
        try {
            StringRedisTemplate redis = redisManager.template(RedisNamespace.CACHE);
            redis.delete(redisManager.key(RedisNamespace.CACHE, STORE_LISTING_CACHE_PREFIX + storeSubjectId));
        } catch (Exception e) {
            // 删除失败可忽略，缓存 TTL 兜底
        }
    }

    private java.util.Set<String> parseStringSet(String json) {
        if (json == null || json.isBlank()) {
            return java.util.Set.of();
        }
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<java.util.Set<String>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    /** 按完整 key 读缓存；Redis 不可用或未命中返回 null（fail-open，回源 DB）。 */
    private String safeGet(String fullKey) {
        try {
            StringRedisTemplate redis = redisManager.template(RedisNamespace.CACHE);
            return redis.opsForValue().get(fullKey);
        } catch (Exception e) {
            return null;
        }
    }

    /** 按完整 key 写缓存；序列化或 Redis 失败时静默忽略。 */
    private void safeSet(String fullKey, Object value, Duration ttl) {
        try {
            StringRedisTemplate redis = redisManager.template(RedisNamespace.CACHE);
            redis.opsForValue().set(fullKey, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            // 缓存写入失败不影响业务
        }
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

    /**
     * 菜单数据变更后失效缓存（供商品/分类/规格的增删改调用）。
     *
     * <p>平台全量菜单 {@code menu:all} 与各门店菜单 {@code menu:store:*} 都要清：
     * 商品上下架 / 分类调整会同时影响两者，漏清任一都会让点单页短暂显示旧菜单。
     */
    public void evictMenuCache() {
        try {
            StringRedisTemplate redis = redisManager.template(RedisNamespace.CACHE);
            redis.delete(redisManager.key(RedisNamespace.CACHE, MENU_CACHE_KEY));
            // 门店维度按前缀清理。
            // 注意：生产禁用 KEYS（redis.conf rename-command），必须用 SCAN 游标迭代，
            // 否则这里会抛 ERR unknown command 'KEYS'，门店菜单缓存永远清不掉。
            // 各 key 仍有 TTL 兜底，即使清理失败也会在 5 分钟内自然过期。
            deleteByPattern(redis, redisManager.key(RedisNamespace.CACHE, STORE_MENU_CACHE_PREFIX + "*"));
        } catch (Exception e) {
            // 删除失败可忽略，缓存 TTL 兜底
        }
    }

    /**
     * 按前缀删除 key（SCAN 游标迭代，禁止使用 KEYS）。
     *
     * <p>逐批 DELETE，避免一次性拉取大量 key 造成 Redis 阻塞；
     * 单次 SCAN 上限 500，配合 TTL 兜底，性能与正确性兼顾。
     */
    private void deleteByPattern(StringRedisTemplate redis, String pattern) {
        Set<String> batch = new java.util.HashSet<>();
        try {
            redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
                org.springframework.data.redis.core.ScanOptions options =
                        org.springframework.data.redis.core.ScanOptions.scanOptions().match(pattern).count(500).build();
                try (org.springframework.data.redis.core.Cursor<byte[]> cursor = connection.scan(options)) {
                    while (cursor.hasNext()) {
                        batch.add(new String(cursor.next(), java.nio.charset.StandardCharsets.UTF_8));
                    }
                } catch (Exception e) {
                    // 游标异常：已收集的批次仍可删除，剩余交给 TTL
                }
                return null;
            });
        } catch (Exception e) {
            // SCAN 不可用时放弃，交给 TTL
            return;
        }
        if (!batch.isEmpty()) {
            redis.delete(batch);
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

