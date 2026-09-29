package com.wuling.product.service;

import com.wuling.common.api.PageResult;
import com.wuling.product.dto.ProductDetailDTO;
import com.wuling.product.dto.StoreProductDTO;
import com.wuling.product.dto.StoreProductPageDTO;
import com.wuling.product.dto.StoreProductDetailDTO;
import com.wuling.product.entity.Product;
import com.wuling.product.entity.ProductCategory;
import com.wuling.product.entity.ProductStore;
import com.wuling.product.mapper.ProductCategoryMapper;
import com.wuling.product.mapper.ProductMapper;
import com.wuling.product.mapper.ProductStoreMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 门店选品：分页、上架状态口径、写操作幂等。 */
class StoreProductServiceTest {

    private static final Long STORE_ID = 101L;

    private ProductMapper productMapper;
    private ProductCategoryMapper categoryMapper;
    private ProductStoreMapper productStoreMapper;
    private ProductQueryService productQueryService;
    private StoreProductService service;

    @BeforeEach
    void setUp() {
        productMapper = mock(ProductMapper.class);
        categoryMapper = mock(ProductCategoryMapper.class);
        productStoreMapper = mock(ProductStoreMapper.class);
        productQueryService = mock(ProductQueryService.class);
        service = new StoreProductService(productMapper, categoryMapper, productStoreMapper, productQueryService);
    }

    private Product product(long id, String productId, String name, long price, Long categoryId) {
        Product p = new Product();
        p.setId(id);
        p.setProductId(productId);
        p.setCode(productId);
        p.setName(name);
        p.setPrice(price);
        p.setCategoryId(categoryId);
        p.setOnSale(1);
        return p;
    }

    @Test
    @DisplayName("列表：基础数据为平台已上架商品，listed 由 product_store 决定")
    void pageMarksListedFromProductStore() {
        when(productStoreMapper.selectListedProductIds(STORE_ID)).thenReturn(List.of(1L));
        when(productMapper.selectList(any())).thenReturn(List.of(
                product(1L, "classic-001", "经典抹茶", 1800L, 10L),
                product(2L, "classic-002", "红豆奶茶", 1600L, 10L)));
        ProductCategory category = new ProductCategory();
        category.setId(10L);
        category.setName("经典系列");
        when(categoryMapper.selectBatchIds(any())).thenReturn(List.of(category));

        StoreProductPageDTO page = service.pageStoreProducts(STORE_ID, 1, 20, null, null, null);

        assertEquals(2, page.getTotal());
        assertEquals(2, page.getRecords().size());
        StoreProductDTO first = page.getRecords().get(0);
        assertEquals("classic-001", first.getId(), "对外展示 ID 用业务编号");
        assertEquals(1L, first.getProductId(), "写库用商品主键");
        assertTrue(first.getListed(), "product_store 有记录 = 已上架");
        assertFalse(page.getRecords().get(1).getListed(), "无记录 = 已下架");
        assertEquals("经典系列", first.getCategoryLabel());
    }

    @Test
    @DisplayName("分页：total 为匹配总数，records 仅当前页")
    void pageSlicesRecords() {
        when(productStoreMapper.selectListedProductIds(STORE_ID)).thenReturn(List.of());
        when(productMapper.selectList(any())).thenReturn(List.of(
                product(1L, "p1", "A", 100L, null),
                product(2L, "p2", "B", 200L, null),
                product(3L, "p3", "C", 300L, null)));

        StoreProductPageDTO page = service.pageStoreProducts(STORE_ID, 2, 2, null, null, null);

        assertEquals(3, page.getTotal(), "total 是匹配总数而非当前页条数");
        assertEquals(1, page.getRecords().size(), "第 2 页只剩 1 条");
        assertEquals("p3", page.getRecords().get(0).getId());
    }

    @Test
    @DisplayName("筛选 listed=true 且本店无上架商品时返回空页，但计数仍反映全量")
    void filtersListedWhenNoneListed() {
        when(productStoreMapper.selectListedProductIds(STORE_ID)).thenReturn(List.of());
        when(productMapper.selectList(any())).thenReturn(List.of(
                product(1L, "p1", "A", 100L, null),
                product(2L, "p2", "B", 200L, null)));

        StoreProductPageDTO page = service.pageStoreProducts(STORE_ID, 1, 20, null, null, true);

        assertEquals(0, page.getTotal(), "本店无上架商品，listed=true 结果为空");
        assertTrue(page.getRecords().isEmpty());
        assertEquals(0, page.getListedTotal());
        assertEquals(2, page.getUnlistedTotal(), "计数基于基础条件，不受 listed 过滤影响");
    }

    @Test
    @DisplayName("计数：三个数自洽（listedTotal + unlistedTotal == total），且随筛选联动")
    void countsAreConsistentWithFilters() {
        // 门店已上架商品主键 1、2；平台在售共 3 个
        when(productStoreMapper.selectListedProductIds(STORE_ID)).thenReturn(List.of(1L, 2L));
        when(productMapper.selectList(any())).thenReturn(List.of(
                product(1L, "p1", "A", 100L, 10L),
                product(2L, "p2", "B", 200L, 10L),
                product(3L, "p3", "C", 300L, 10L)));

        StoreProductPageDTO all = service.pageStoreProducts(STORE_ID, 1, 20, null, null, null);

        assertEquals(3, all.getTotal());
        assertEquals(2, all.getListedTotal());
        assertEquals(1, all.getUnlistedTotal());
        assertEquals(all.getTotal(), all.getListedTotal() + all.getUnlistedTotal(),
                "全部 = 已上架 + 已下架，三个数必须自洽");

        // listed 是「看哪一档」而非筛选条件：切档只改 total，计数保持稳定
        StoreProductPageDTO unlisted = service.pageStoreProducts(STORE_ID, 1, 20, null, null, false);
        assertEquals(1, unlisted.getTotal(), "已下架档位只剩 1 条");
        assertEquals(2, unlisted.getListedTotal(), "计数不随 listed 过滤变化");
        assertEquals(1, unlisted.getUnlistedTotal());
        assertEquals(all.getTotal(), unlisted.getListedTotal() + unlisted.getUnlistedTotal(),
                "全部 = 已上架 + 已下架（与当前档位无关）");
    }

    @Test
    @DisplayName("分类分布：与总数同源，Σ分类 == 全部，且按数量降序")
    void categoryCountsAreConsistentWithTotal() {
        when(productStoreMapper.selectListedProductIds(STORE_ID)).thenReturn(List.of(1L));
        when(productMapper.selectList(any())).thenReturn(List.of(
                product(1L, "p1", "A", 100L, 10L),
                product(2L, "p2", "B", 200L, 10L),
                product(3L, "p3", "C", 300L, 20L)));
        ProductCategory c10 = new ProductCategory();
        c10.setId(10L);
        c10.setName("草本养生茶");
        ProductCategory c20 = new ProductCategory();
        c20.setId(20L);
        c20.setName("季节限定");
        when(categoryMapper.selectBatchIds(any())).thenReturn(List.of(c10, c20));

        StoreProductPageDTO page = service.pageStoreProducts(STORE_ID, 1, 20, null, null, null);

        List<StoreProductPageDTO.CategoryCount> counts = page.getCategoryCounts();
        assertEquals(2, counts.size(), "两个分类各一条");
        // 降序：10 有 2 个 -> 在前
        assertEquals(10L, counts.get(0).getCategoryId());
        assertEquals("草本养生茶", counts.get(0).getLabel());
        assertEquals(2, counts.get(0).getCount());
        assertEquals(20L, counts.get(1).getCategoryId());
        assertEquals(1, counts.get(1).getCount());

        long sum = counts.stream().mapToLong(StoreProductPageDTO.CategoryCount::getCount).sum();
        assertEquals(page.getListedTotal() + page.getUnlistedTotal(), sum,
                "Σ分类数必须等于「全部」，否则分类 Tab 与状态 Tab 对不上");
    }

    @Test
    @DisplayName("分类分布：不随 listed 档位变化（口径统一）")
    void categoryCountsIgnoreListingFilter() {
        when(productStoreMapper.selectListedProductIds(STORE_ID)).thenReturn(List.of(1L));
        when(productMapper.selectList(any())).thenReturn(List.of(
                product(1L, "p1", "A", 100L, 10L),
                product(2L, "p2", "B", 200L, 10L)));
        ProductCategory c10 = new ProductCategory();
        c10.setId(10L);
        c10.setName("草本养生茶");
        when(categoryMapper.selectBatchIds(any())).thenReturn(List.of(c10));

        StoreProductPageDTO listed = service.pageStoreProducts(STORE_ID, 1, 20, null, null, true);
        StoreProductPageDTO unlisted = service.pageStoreProducts(STORE_ID, 1, 20, null, null, false);

        assertEquals(1, listed.getTotal(), "已上架档 1 条");
        assertEquals(1, unlisted.getTotal(), "已下架档 1 条");
        // 两档的分类分布完全一致（都反映全量）
        assertEquals(2, listed.getCategoryCounts().get(0).getCount());
        assertEquals(2, unlisted.getCategoryCounts().get(0).getCount(),
                "分类分布不随档位变化，避免数字随切档跳动");
    }

    @Test
    @DisplayName("计数：跟随关键词筛选，避免「全部」与两个档位口径不一致")
    void countsFollowKeywordFilter() {
        when(productStoreMapper.selectListedProductIds(STORE_ID)).thenReturn(List.of(1L));
        // 关键词命中 1、3；其中 1 已上架、3 未上架
        when(productMapper.selectList(any())).thenReturn(List.of(
                product(1L, "p1", "A", 100L, null),
                product(3L, "p3", "C", 300L, null)));

        StoreProductPageDTO page = service.pageStoreProducts(STORE_ID, 1, 20, "p", null, null);

        assertEquals(2, page.getTotal());
        assertEquals(1, page.getListedTotal(), "关键词命中的已上架数");
        assertEquals(1, page.getUnlistedTotal(), "关键词命中的未上架数");
        assertEquals(page.getTotal(), page.getListedTotal() + page.getUnlistedTotal(),
                "历史 bug：计数未带 keyword，导致 全部 1 / 已上架 27 / 已下架 1 三数不自洽");
    }

    @Test
    @DisplayName("上架：插入 product_store 关联")
    void updateListingAddsLinkWhenListing() {
        when(productMapper.selectById(1L)).thenReturn(product(1L, "p1", "A", 100L, null));
        when(productStoreMapper.selectCount(any())).thenReturn(0L);

        service.updateListing(STORE_ID, 1L, true);

        verify(productStoreMapper).insert(any(ProductStore.class));
        verify(productStoreMapper, never()).physicalDeleteByStoreAndProduct(anyLong(), anyLong());
    }

    @Test
    @DisplayName("上架幂等：已有记录不重复插入")
    void updateListingIsIdempotent() {
        when(productMapper.selectById(1L)).thenReturn(product(1L, "p1", "A", 100L, null));
        when(productStoreMapper.selectCount(any())).thenReturn(1L);

        service.updateListing(STORE_ID, 1L, true);

        verify(productStoreMapper, never()).insert(any(ProductStore.class));
    }

    @Test
    @DisplayName("下架：物理删除关联（避免唯一键残留导致重新上架冲突）")
    void updateListingDeletesLinkWhenUnlisting() {
        when(productMapper.selectById(1L)).thenReturn(product(1L, "p1", "A", 100L, null));

        service.updateListing(STORE_ID, 1L, false);

        verify(productStoreMapper).physicalDeleteByStoreAndProduct(STORE_ID, 1L);
    }

    @Test
    @DisplayName("平台已下架商品不可被门店上架")
    void updateListingRejectsPlatformOffSale() {
        Product offSale = product(1L, "p1", "A", 100L, null);
        offSale.setOnSale(0);
        when(productMapper.selectById(1L)).thenReturn(offSale);

        assertThrows(com.wuling.common.exception.BusinessException.class,
                () -> service.updateListing(STORE_ID, 1L, true));
        verify(productStoreMapper, never()).insert(any(ProductStore.class));
    }

    @Test
    @DisplayName("详情：补齐门店维度 listed，且平台字段来自消费端详情")
    void storeProductDetailCarriesStoreListingState() {
        Product p = product(1L, "classic-001", "五窨茉莉抹茶", 1390L, 5L);
        when(productMapper.selectOne(any())).thenReturn(p);

        ProductDetailDTO base = new ProductDetailDTO();
        base.setId("classic-001");
        base.setName("五窨茉莉抹茶");
        when(productQueryService.getProductDetail("classic-001")).thenReturn(base);
        when(productStoreMapper.selectCount(any())).thenReturn(1L);
        ProductCategory category = new ProductCategory();
        category.setId(5L);
        category.setName("传统原叶茶");
        when(categoryMapper.selectById(5L)).thenReturn(category);

        StoreProductDetailDTO dto = service.getStoreProductDetail(STORE_ID, "classic-001");

        assertEquals("classic-001", dto.getId());
        assertEquals("五窨茉莉抹茶", dto.getName());
        assertTrue(dto.getPlatformListed(), "平台已上架商品 platformListed 恒为 true");
        assertTrue(dto.getListed(), "product_store 有记录即为本店已上架");
        assertEquals(5L, dto.getCategoryId());
        assertEquals("传统原叶茶", dto.getCategoryLabel(), "详情页需展示分类名");
    }

    @Test
    @DisplayName("详情：分类缺失时 categoryLabel 回落为 '-'，不抛异常")
    void storeProductDetailToleratesMissingCategory() {
        when(productMapper.selectOne(any())).thenReturn(product(1L, "p1", "A", 100L, 5L));
        when(productQueryService.getProductDetail("p1")).thenReturn(new ProductDetailDTO());
        when(productStoreMapper.selectCount(any())).thenReturn(0L);
        when(categoryMapper.selectById(5L)).thenReturn(null);

        StoreProductDetailDTO dto = service.getStoreProductDetail(STORE_ID, "p1");

        assertEquals("-", dto.getCategoryLabel());
    }

    @Test
    @DisplayName("详情：本店未上架时 listed=false（仍可查看，只读展示）")
    void storeProductDetailUnlistedWhenNoLink() {
        when(productMapper.selectOne(any())).thenReturn(product(1L, "p1", "A", 100L, null));
        when(productQueryService.getProductDetail("p1")).thenReturn(new ProductDetailDTO());
        when(productStoreMapper.selectCount(any())).thenReturn(0L);

        StoreProductDetailDTO dto = service.getStoreProductDetail(STORE_ID, "p1");

        assertFalse(dto.getListed(), "无关联记录即本店已下架");
    }

    @Test
    @DisplayName("详情：平台已下架商品返回 null（门店不可见）")
    void storeProductDetailNullForOffSale() {
        Product offSale = product(1L, "p1", "A", 100L, null);
        offSale.setOnSale(0);
        when(productMapper.selectOne(any())).thenReturn(offSale);

        assertNull(service.getStoreProductDetail(STORE_ID, "p1"));
        verify(productQueryService, never()).getProductDetail(any());
    }

    @Test
    @DisplayName("详情：商品不存在返回 null，且不触发门店关联查询")
    void storeProductDetailNullForUnknown() {
        when(productMapper.selectOne(any())).thenReturn(null);

        assertNull(service.getStoreProductDetail(STORE_ID, "missing"));
        verify(productStoreMapper, never()).selectCount(any());
    }

    @Test
    @DisplayName("批量上架：去重后逐条上架")
    void updateListingBatchDeduplicates() {
        when(productMapper.selectById(any())).thenReturn(product(1L, "p1", "A", 100L, null));
        when(productStoreMapper.selectCount(any())).thenReturn(0L);

        int affected = service.updateListingBatch(STORE_ID, List.of(1L, 1L, 2L), true);

        assertEquals(2, affected, "重复 id 只处理一次");
    }
}
