package com.wuling.product.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.common.redis.RedisManager;
import com.wuling.common.redis.RedisNamespace;
import com.wuling.product.dto.AdminProductDTO;
import com.wuling.product.entity.Product;
import com.wuling.product.mapper.ProductCategoryMapper;
import com.wuling.product.mapper.ProductMapper;
import com.wuling.product.mapper.ProductSpecMapper;
import com.wuling.product.mapper.ProductStoreMapper;
import com.wuling.product.port.SubjectQueryPort;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 门店点单商品的排序能力：后台列表白名单排序 + 小程序菜单按排序号展示。
 */
class ProductSortQueryTest {

    private ProductMapper productMapper;
    private ProductQueryService service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Product.class);
    }

    @BeforeEach
    void setUp() {
        productMapper = mock(ProductMapper.class);
        ProductCategoryMapper categoryMapper = mock(ProductCategoryMapper.class);
        ProductSpecMapper specMapper = mock(ProductSpecMapper.class);
        ProductStoreMapper storeMapper = mock(ProductStoreMapper.class);
        RedisManager redisManager = mock(RedisManager.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisManager.template(any(RedisNamespace.class))).thenReturn(redis);
        when(redisManager.key(any(RedisNamespace.class), anyString()))
                .thenAnswer(inv -> inv.getArgument(0, RedisNamespace.class).key(inv.getArgument(1)));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(categoryMapper.selectList(any())).thenReturn(List.of());
        when(productMapper.selectList(any())).thenReturn(List.of());
        when(specMapper.selectList(any())).thenReturn(List.of());
        when(productMapper.selectPage(any(), any())).thenReturn(emptyPage());
        when(productMapper.selectBatchIds(any())).thenReturn(List.of());

        service = new ProductQueryService(productMapper, categoryMapper, specMapper,
                storeMapper, mock(SubjectQueryPort.class), new ObjectMapper(), redisManager);
    }

    private static Page<Product> emptyPage() {
        Page<Product> page = new Page<>(1, 10);
        page.setRecords(List.of());
        page.setTotal(0);
        return page;
    }

    @SuppressWarnings("unchecked")
    private String capturedAdminListSql(long current, long size, String sortBy, String sortOrder) {
        service.pageAdminProducts(current, size, null, sortBy, sortOrder);
        ArgumentCaptor<Wrapper<Product>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(productMapper, org.mockito.Mockito.atLeastOnce()).selectPage(any(), captor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) captor.getValue();
        return wrapper.getSqlSegment().replaceAll("\\s+", " ").toLowerCase();
    }

    @Test
    @DisplayName("后台列表默认按排序号升序、同序号按 id 升序")
    void adminListDefaultsToSortThenId() {
        String sql = capturedAdminListSql(1, 10, null, null);

        assertTrue(sql.contains("order by sort asc"), "默认必须按排序号升序：" + sql);
        assertTrue(sql.indexOf("sort asc") < sql.indexOf("id asc"), "同排序号必须按 id 兜底：" + sql);
    }

    @Test
    @DisplayName("后台列表支持按创建时间倒序")
    void adminListSortsByCreateTimeDesc() {
        String sql = capturedAdminListSql(1, 10, "createTime", "desc");

        assertTrue(sql.contains("order by create_time desc"), "必须按请求列降序：" + sql);
        assertTrue(!sql.contains("sort asc"), "显式排序时不应再叠加默认排序：" + sql);
    }

    @Test
    @DisplayName("非法排序列回落默认排序，不进入 SQL")
    void adminListIgnoresUnknownSortColumn() {
        String sql = capturedAdminListSql(1, 10, "price; drop table product", "desc");

        assertTrue(sql.contains("order by sort asc"), "非法列必须回落默认排序：" + sql);
        assertTrue(!sql.contains("drop table"), "用户输入不得进入 SQL：" + sql);
    }

    @Test
    @DisplayName("后台列表返回排序号，空值按 0")
    void adminListExposesSort() {
        Product product = new Product();
        product.setId(7L);
        product.setProductId("P-7");
        product.setCode("P-7");
        product.setName("测试商品");
        product.setSort(3);
        Page<Product> page = new Page<>(1, 10);
        page.setRecords(List.of(product));
        page.setTotal(1);
        when(productMapper.selectPage(any(), any())).thenReturn(page);

        AdminProductDTO dto = service.pageAdminProducts(1, 10, null, null, null).getRecords().get(0);

        assertEquals(3, dto.getSort());
    }

    @Test
    @DisplayName("小程序菜单按排序号升序取商品")
    void menuOrdersProductsBySort() {
        service.getMenu();

        ArgumentCaptor<Wrapper<Product>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(productMapper).selectList(captor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) captor.getValue();
        String sql = wrapper.getSqlSegment().replaceAll("\\s+", " ").toLowerCase();

        assertTrue(sql.contains("order by sort asc"), "菜单商品必须按排序号升序：" + sql);
        assertTrue(sql.indexOf("sort asc") < sql.indexOf("id asc"), "同排序号按 id 兜底：" + sql);
    }
}
