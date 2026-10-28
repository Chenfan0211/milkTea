package com.wuling.product.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.product.dto.MallProductCardDTO;
import com.wuling.product.dto.MallSkuInternalView;
import com.wuling.product.entity.MallProduct;
import com.wuling.product.entity.MallSku;
import com.wuling.product.mapper.MallCategoryMapper;
import com.wuling.product.mapper.MallProductMapper;
import com.wuling.product.mapper.MallSkuMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MallProductQueryServiceTest {

    private MallProductMapper productMapper;
    private MallSkuMapper skuMapper;
    private MallProductQueryService service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), MallProduct.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), MallSku.class);
    }

    @BeforeEach
    void setUp() {
        productMapper = mock(MallProductMapper.class);
        skuMapper = mock(MallSkuMapper.class);
        service = new MallProductQueryService(
                productMapper, mock(MallCategoryMapper.class), skuMapper, new ObjectMapper());
    }

    @Test
    @DisplayName("App 商品分页查询先按任一 SKU 可售排序，再按运营排序和 ID 稳定沉底")
    @SuppressWarnings("unchecked")
    void appPageQueryPutsSoldOutProductsLastWithStableTieBreakers() {
        MallProductMapper mapper = mock(MallProductMapper.class);
        Page<MallProduct> page = new Page<>(1, 20);
        page.setRecords(List.of());
        page.setTotal(0);
        when(mapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);

        MallProductQueryService queryService = new MallProductQueryService(
                mapper, mock(MallCategoryMapper.class), mock(MallSkuMapper.class), new ObjectMapper());
        queryService.pageAppProducts(12L, 1, 20);

        ArgumentCaptor<Wrapper<MallProduct>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectPage(any(Page.class), captor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) captor.getValue();
        String sql = wrapper.getSqlSegment().replaceAll("\\s+", " ").toLowerCase();
        assertTrue(sql.contains("category_id"), "必须按分类过滤");
        assertTrue(sql.contains("status"), "必须只查询在售商品");
        assertTrue(sql.contains("exists"), "必须按是否存在可售 SKU 判断售罄");
        assertTrue(sql.contains("s.stock > s.locked_stock"), "可用库存必须使用 stock-locked_stock");
        assertTrue(sql.indexOf("case when exists") < sql.indexOf("sort"), "可售商品必须排在售罄商品之前");
        assertTrue(sql.indexOf("sort") < sql.lastIndexOf("id desc"), "排序必须有稳定的运营排序和 ID 兜底");
    }

    @Test
    @DisplayName("内部 SKU 查询返回分账快照字段和可用库存")
    void internalQueryReturnsCommercialSnapshotAndAvailableStock() {
        MallSku sku = new MallSku();
        sku.setId(1L);
        sku.setProductId(9L);
        sku.setSkuCode("SKU-1");
        sku.setSpecValue("红色");
        sku.setSalePrice(1000L);
        sku.setOriginalPrice(1200L);
        sku.setCostPrice(500L);
        sku.setPlatformCommission(100L);
        sku.setDistributionCommission(120L);
        sku.setSupplierSubjectId(401L);
        sku.setStock(8);
        sku.setLockedStock(3);

        MallProduct product = new MallProduct();
        product.setId(9L);
        product.setProductCode("P-9");
        product.setName("茶杯");
        product.setStatus("ON_SALE");
        product.setAttributionStoreSubjectId(101L);
        product.setAttributionChannelSubjectId(301L);
        product.setSplitRuleId(7L);

        when(skuMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(sku));
        when(productMapper.selectBatchIds(any())).thenReturn(List.of(product));

        MallSkuInternalView view = service.queryInternalSkus(List.of(1L)).get(0);

        assertEquals(1L, view.getSkuId());
        assertEquals("P-9", view.getProductCode());
        assertEquals(101L, view.getAttributionStoreSubjectId());
        assertEquals(301L, view.getAttributionChannelSubjectId());
        assertEquals(7L, view.getSplitRuleId());
        assertEquals(1000L, view.getSalePrice());
        assertEquals(500L, view.getCostPrice());
        assertEquals(100L, view.getPlatformCommission());
        assertEquals(120L, view.getDistributionCommission());
        assertEquals(401L, view.getSupplierSubjectId());
        assertEquals(5, view.getAvailableStock());
        assertTrue(view.getOnSale());
    }
    @Test
    @DisplayName("全部 SKU 无可用库存时商品卡标记售罄")
    void cardIsSoldOutWhenEverySkuHasNoAvailableStock() {
        MallProduct product = new MallProduct();
        product.setId(9L);
        product.setProductCode("P-9");
        product.setName("茶杯");
        product.setStatus("ON_SALE");
        product.setSort(1);

        Page<MallProduct> page = new Page<>(1, 20);
        page.setRecords(List.of(product));
        page.setTotal(1);
        when(productMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);

        MallSku soldOut = new MallSku();
        soldOut.setId(1L);
        soldOut.setProductId(9L);
        soldOut.setSalePrice(1000L);
        soldOut.setStock(1);
        soldOut.setLockedStock(1);
        when(skuMapper.selectList(any())).thenReturn(List.of(soldOut));

        MallProductCardDTO card = service.pageAppProducts(null, 1, 20).getRecords().get(0);

        assertTrue(card.getSoldOut());
        assertFalse(card.getAvailableStock() > 0);
    }
}

