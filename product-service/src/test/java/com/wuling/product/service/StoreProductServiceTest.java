package com.wuling.product.service;

import com.wuling.common.api.PageResult;
import com.wuling.product.dto.ProductDetailDTO;
import com.wuling.product.dto.StoreProductDTO;
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

        PageResult<StoreProductDTO> page = service.pageStoreProducts(STORE_ID, 1, 20, null, null, null);

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

        PageResult<StoreProductDTO> page = service.pageStoreProducts(STORE_ID, 2, 2, null, null, null);

        assertEquals(3, page.getTotal(), "total 是匹配总数而非当前页条数");
        assertEquals(1, page.getRecords().size(), "第 2 页只剩 1 条");
        assertEquals("p3", page.getRecords().get(0).getId());
    }

    @Test
    @DisplayName("筛选 listed=true 时，本店无任何上架商品直接返回空页")
    void filtersListedWhenNoneListed() {
        when(productStoreMapper.selectListedProductIds(STORE_ID)).thenReturn(List.of());

        PageResult<StoreProductDTO> page = service.pageStoreProducts(STORE_ID, 1, 20, null, null, true);

        assertEquals(0, page.getTotal());
        assertTrue(page.getRecords().isEmpty());
        verify(productMapper, never()).selectList(any());
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

        StoreProductDetailDTO dto = service.getStoreProductDetail(STORE_ID, "classic-001");

        assertEquals("classic-001", dto.getId());
        assertEquals("五窨茉莉抹茶", dto.getName());
        assertTrue(dto.getPlatformListed(), "平台已上架商品 platformListed 恒为 true");
        assertTrue(dto.getListed(), "product_store 有记录即为本店已上架");
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
