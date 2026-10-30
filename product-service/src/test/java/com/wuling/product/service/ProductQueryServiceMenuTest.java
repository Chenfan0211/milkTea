package com.wuling.product.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.common.redis.RedisManager;
import com.wuling.common.redis.RedisNamespace;
import com.wuling.product.dto.MenuDTO;
import com.wuling.product.entity.Product;
import com.wuling.product.entity.ProductCategory;
import com.wuling.product.mapper.ProductCategoryMapper;
import com.wuling.product.mapper.ProductMapper;
import com.wuling.product.mapper.ProductSpecMapper;
import com.wuling.product.mapper.ProductStoreMapper;
import com.wuling.product.port.SubjectQueryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 菜单接口按门店选品过滤的回归测试。
 *
 * <p>历史 bug：菜单缓存只有一个 key（menu:all），且缓存命中时直接返回，
 * 跳过了 filterMenuByStore；热缓存下所有门店（含未配选品的测试门店）
 * 都会看到平台全量菜单。这里锁定修复后的行为。
 */
class ProductQueryServiceMenuTest {

    private static final Long STORE_ID = 101L;

    private ProductMapper productMapper;
    private ProductCategoryMapper categoryMapper;
    private ProductSpecMapper specMapper;
    private ProductStoreMapper productStoreMapper;
    private SubjectQueryPort subjectQueryPort;
    private RedisManager redisManager;
    private StringRedisTemplate redis;

    private ProductQueryService service;

    @BeforeEach
    void setUp() {
        productMapper = mock(ProductMapper.class);
        categoryMapper = mock(ProductCategoryMapper.class);
        specMapper = mock(ProductSpecMapper.class);
        productStoreMapper = mock(ProductStoreMapper.class);
        subjectQueryPort = mock(SubjectQueryPort.class);
        redisManager = mock(RedisManager.class);
        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);

        when(redisManager.template(any(RedisNamespace.class))).thenReturn(redis);
        when(redisManager.key(any(RedisNamespace.class), anyString()))
                .thenAnswer(inv -> inv.getArgument(0, RedisNamespace.class).key(inv.getArgument(1)));
        when(redis.opsForValue()).thenReturn(valueOps);
        // 默认：所有缓存未命中，强制走 DB 组装 + 过滤
        when(valueOps.get(anyString())).thenReturn(null);
        when(subjectQueryPort.findStoreBusinessStatus(any())).thenReturn("open");

        service = new ProductQueryService(productMapper, categoryMapper, specMapper,
                productStoreMapper, subjectQueryPort, new ObjectMapper(), redisManager);
    }

    private Product product(long id, String productId, String name) {
        Product p = new Product();
        p.setId(id);
        p.setProductId(productId);
        p.setCode(productId);
        p.setName(name);
        p.setPrice(1800L);
        p.setCategoryId(10L);
        p.setOnSale(1);
        return p;
    }

    private ProductCategory category(long id, String code, String name) {
        ProductCategory c = new ProductCategory();
        c.setId(id);
        c.setCode(code);
        c.setName(name);
        c.setType("CATEGORY");
        c.setEnabled(1);
        return c;
    }

    private int countProducts(List<MenuDTO.MenuTab> tabs) {
        int n = 0;
        for (MenuDTO.MenuTab tab : tabs) {
            for (MenuDTO.MenuGroup g : tab.getGroups()) {
                for (MenuDTO.MenuCategory c : g.getCategories()) {
                    n += c.getProducts().size();
                }
            }
        }
        return n;
    }

    /** 平台 3 个上架商品；本店只上架其中 1 个。 */
    private void givenPlatformAndListing() {
        when(categoryMapper.selectList(any()))
                .thenReturn(List.of(category(10L, "herbal", "草本养生茶")));
        when(productMapper.selectList(any())).thenReturn(List.of(
                product(1L, "classic-002", "轻乳茶"),
                product(2L, "classic-003", "茉莉冰茶"),
                product(3L, "classic-004", "陈皮普洱")));
        when(specMapper.selectList(any())).thenReturn(List.of());
        // 本店只上架商品主键 1（业务编号 classic-002）
        when(productStoreMapper.selectListedProductIds(STORE_ID)).thenReturn(List.of(1L));
        when(productMapper.selectBatchIds(any())).thenReturn(List.of(product(1L, "classic-002", "轻乳茶")));
    }

    @Test
    @DisplayName("传门店：只返回本店已上架商品（缓存未命中路径）")
    void filtersByStoreSelection() {
        givenPlatformAndListing();

        List<MenuDTO.MenuTab> menu = service.getMenu(STORE_ID);

        assertEquals(1, countProducts(menu), "只应保留本店上架的 1 个商品");
        assertEquals("classic-002", menu.get(0).getGroups().get(0).getCategories().get(0)
                .getProducts().get(0).getId());
    }

    @Test
    @DisplayName("回归：平台全量缓存命中时，传门店仍必须被过滤（历史 bug）")
    void platformCacheHitStillFiltersByStore() throws Exception {
        givenPlatformAndListing();

        // 模拟 menu:all 缓存命中（内容是平台全量 3 个商品）
        String platformJson = new ObjectMapper().writeValueAsString(buildPlatformMenu());
        ValueOperations<String, String> valueOps = redis.opsForValue();
        when(valueOps.get(redisManager.key(RedisNamespace.CACHE, "menu:all"))).thenReturn(platformJson);

        List<MenuDTO.MenuTab> menu = service.getMenu(STORE_ID);

        assertEquals(1, countProducts(menu), "平台缓存命中也不得绕过门店过滤");
    }

    @Test
    @DisplayName("门店未配置任何选品：返回空菜单，不回落平台全量")
    void emptySelectionYieldsEmptyMenu() {
        when(categoryMapper.selectList(any()))
                .thenReturn(List.of(category(10L, "herbal", "草本养生茶")));
        when(productMapper.selectList(any())).thenReturn(List.of(
                product(1L, "classic-002", "轻乳茶"),
                product(2L, "classic-003", "茉莉冰茶")));
        when(specMapper.selectList(any())).thenReturn(List.of());
        when(productStoreMapper.selectListedProductIds(STORE_ID)).thenReturn(List.of());

        List<MenuDTO.MenuTab> menu = service.getMenu(STORE_ID);

        assertTrue(menu.isEmpty(), "未配置选品的门店必须返回空菜单");
    }

    @Test
    @DisplayName("不传门店：返回平台全量菜单（不过滤）")
    void platformMenuWithoutStore() {
        givenPlatformAndListing();

        List<MenuDTO.MenuTab> menu = service.getMenu();

        assertEquals(3, countProducts(menu), "不传门店应返回平台全量");
    }

    @Test
    @DisplayName("门店菜单命中自身缓存：直接返回，不再执行过滤查询")
    void storeMenuCacheHitShortCircuits() throws Exception {
        String filteredJson = new ObjectMapper().writeValueAsString(buildMenuWithProducts(List.of("classic-002")));
        ValueOperations<String, String> valueOps = redis.opsForValue();
        when(valueOps.get(redisManager.key(RedisNamespace.CACHE, "menu:store:" + STORE_ID)))
                .thenReturn(filteredJson);

        List<MenuDTO.MenuTab> menu = service.getMenu(STORE_ID);

        assertEquals(1, countProducts(menu));
        Mockito.verify(productStoreMapper, Mockito.never()).selectListedProductIds(any());
    }

    @Test
    @DisplayName("停用门店：不读菜单缓存，也不回源")
    void closedStoreReturnsEmptyMenu() {
        when(subjectQueryPort.findStoreBusinessStatus(STORE_ID)).thenReturn("closed");

        List<MenuDTO.MenuTab> menu = service.getMenu(STORE_ID);

        assertTrue(menu.isEmpty(), "停用门店不得继续展示商品");
        Mockito.verify(categoryMapper, Mockito.never()).selectList(any());
    }

    private List<MenuDTO.MenuTab> buildPlatformMenu() {
        return buildMenuWithProducts(List.of("classic-002", "classic-003", "classic-004"));
    }

    private List<MenuDTO.MenuTab> buildMenuWithProducts(List<String> productIds) {
        MenuDTO.MenuCategory category = new MenuDTO.MenuCategory();
        MenuDTO.MenuGroup group = new MenuDTO.MenuGroup();
        MenuDTO.MenuTab tab = new MenuDTO.MenuTab();
        group.setId("all");
        group.setLabel("全部");
        category.setId("herbal");
        category.setLabel("草本养生茶");
        category.setProducts(productIds.stream().map(id -> {
            MenuDTO.MenuProduct p = new MenuDTO.MenuProduct();
            p.setId(id);
            p.setName(id);
            return p;
        }).toList());
        group.setCategories(List.of(category));
        tab.setId("menu");
        tab.setLabel("菜单");
        tab.setGroups(List.of(group));
        return List.of(tab);
    }
}
