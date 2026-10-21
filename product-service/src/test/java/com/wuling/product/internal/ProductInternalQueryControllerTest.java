package com.wuling.product.internal;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wuling.product.entity.Product;
import com.wuling.product.mapper.ProductMapper;
import com.wuling.product.mapper.ProductStoreMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 舌象报告商品卡内部接口：名称匹配、只查在售、空入参短路、名称上限。
 */
class ProductInternalQueryControllerTest {

    private ProductMapper productMapper;
    private ProductInternalQueryController controller;

    @BeforeEach
    void setUp() {
        // LambdaQueryWrapper 的列名解析依赖 TableInfo；纯单测里手动初始化一次。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Product.class);
        productMapper = mock(ProductMapper.class);
        controller = new ProductInternalQueryController(productMapper, mock(ProductStoreMapper.class));
    }

    @Test
    @DisplayName("按归一化商品名命中并返回商品卡字段")
    @SuppressWarnings("unchecked")
    void returnsCardsForNormalizedNames() {
        when(productMapper.selectList(any())).thenReturn(List.of(
                product("herbal-001", "桂香暖润茶", "/assets/images/3x/menu-product.jpg", 1290L, 1500L,
                        "[\"年度热销\",\"草本养生\"]", "[\"痰湿质\",\"阳虚质\"]")
        ));

        List<Map<String, Object>> cards = controller.productCards(List.of(" 桂香暖润茶 "));

        assertEquals(1, cards.size());
        Map<String, Object> card = cards.get(0);
        assertEquals("herbal-001", card.get("productId"));
        assertEquals("桂香暖润茶", card.get("name"));
        assertEquals("/assets/images/3x/menu-product.jpg", card.get("image"));
        assertEquals(1290L, card.get("price"));
        assertEquals(1500L, card.get("originalPrice"));
        assertEquals(List.of("年度热销", "草本养生"), card.get("tags"));
        assertEquals(List.of("痰湿质", "阳虚质"), card.get("suitableConstitutions"), "卡片必须返回适宜体质");

        ArgumentCaptor<Wrapper<Product>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(productMapper).selectList(captor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) captor.getValue();
        // 参数值在 MP 中是惰性渲染的：必须先取 SQL 片段，参数表才有值
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("on_sale"), "查询必须限定在售商品（on_sale = 1）");
        assertTrue(sql.contains("name"), "查询必须按商品名匹配");

        Collection<Object> params = sqlParams(wrapper);
        assertTrue(params.contains(1), "查询必须带上 on_sale = 1 条件");
        assertTrue(params.contains("桂香暖润茶"), "名称需 trim 后再查询");
    }

    @Test
    @DisplayName("未命中的报告商品名不出现在结果里")
    @SuppressWarnings("unchecked")
    void dropsUnmatchedNames() {
        when(productMapper.selectList(any())).thenReturn(List.of(
                product("classic-001", "五窨茉莉抹茶", "/assets/images/3x/menu-product.jpg", 1390L, 1600L, null)
        ));

        List<Map<String, Object>> cards = controller.productCards(Arrays.asList("滇绿", "五窨茉莉抹茶"));

        assertEquals(1, cards.size());
        assertEquals("classic-001", cards.get(0).get("productId"));
        assertEquals(List.of(), cards.get(0).get("tags"), "tags 缺失时返回空数组，不返回 null");
    }

    @Test
    @DisplayName("名称为空或全为空白时不查库")
    void skipsQueryWhenNamesAreBlank() {
        assertTrue(controller.productCards(null).isEmpty());
        assertTrue(controller.productCards(List.of("  ", "")).isEmpty());
        verify(productMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("重复名称只查一次，且最多取 20 个")
    @SuppressWarnings("unchecked")
    void deduplicatesAndCapsNames() {
        when(productMapper.selectList(any())).thenReturn(List.of());
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            names.add("茶 " + i);
        }
        names.add("茶 0");

        List<Map<String, Object>> cards = controller.productCards(names);

        assertTrue(cards.isEmpty());
        ArgumentCaptor<Wrapper<Product>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(productMapper).selectList(captor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) captor.getValue();
        Collection<Object> params = sqlParams(wrapper);
        long queried = params.stream().filter(value -> value instanceof String).count();
        assertEquals(20, queried, "名称数量超过上限时只取前 20 个");
        assertTrue(params.contains("茶 19"), "第 20 个名称仍在查询范围内");
        assertFalse(params.contains("茶 20"), "超出上限的名称不再参与查询");
    }

    /** 取 MP 查询参数：参数是惰性渲染的，必须先取一次 SQL 片段。 */
    private static Collection<Object> sqlParams(AbstractWrapper<?, ?, ?> wrapper) {
        wrapper.getSqlSegment();
        return wrapper.getParamNameValuePairs().values();
    }

    private static Product product(String productId, String name, String image,
                                   Long price, Long originalPrice, String tags) {
        return product(productId, name, image, price, originalPrice, tags, null);
    }

    private static Product product(String productId, String name, String image,
                                   Long price, Long originalPrice, String tags, String constitutions) {
        Product product = new Product();
        product.setProductId(productId);
        product.setName(name);
        product.setImage(image);
        product.setPrice(price);
        product.setOriginalPrice(originalPrice);
        product.setTags(tags);
        product.setSuitableConstitutions(constitutions);
        product.setOnSale(1);
        return product;
    }
}