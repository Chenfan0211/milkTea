package com.wuling.product.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 后台商品列表的图片字段映射。
 *
 * <p>背景：2026-11 线上「商品管理」整列图片显示为「—」。根因是
 * {@link AdminProductDTO} 只有 galleryImage、没有 image 字段，
 * 列表接口组装 DTO 时把 product.image 丢掉了，前端 row.image 恒为
 * undefined 于是走了空值分支。这里锁住两个约定：
 *
 * <ol>
 *   <li>image 必须原样下发，不能丢；</li>
 *   <li>image 为空时【不得】用 galleryImage 兜底——后台需要能看出
 *       「缩略图未上传」，展示层的回退由前端按 image -> galleryImage
 *       -> 占位图 处理。</li>
 * </ol>
 */
class AdminProductImageMappingTest {

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
        when(productMapper.selectBatchIds(any())).thenReturn(List.of());

        service = new ProductQueryService(productMapper, categoryMapper, specMapper,
                storeMapper, mock(SubjectQueryPort.class), new ObjectMapper(), redisManager);
    }

    /** 让分页查询返回指定的一行商品，取回组装后的 DTO。 */
    private AdminProductDTO singleRow(Product product) {
        Page<Product> page = new Page<>(1, 10);
        page.setRecords(List.of(product));
        page.setTotal(1);
        when(productMapper.selectPage(any(), any())).thenReturn(page);

        List<AdminProductDTO> records = service.pageAdminProducts(1, 10, null, null, null).getRecords();
        assertEquals(1, records.size(), "应返回一行商品");
        return records.get(0);
    }

    private Product productWithImages(String image, String galleryImage) {
        Product product = new Product();
        product.setId(1L);
        product.setProductId("DEMO-1011");
        product.setCode("DEMO-1011");
        product.setName("【演示】雪梨茉莉润燥");
        product.setImage(image);
        product.setGalleryImage(galleryImage);
        return product;
    }

    @Test
    @DisplayName("列表下发商品缩略图 image，修复整列显示「—」")
    void adminListExposesThumbnailImage() {
        String image = "/api/v1/files/public/thumb.jpg";
        AdminProductDTO dto = singleRow(productWithImages(image, "/api/v1/files/public/detail.jpg"));

        assertEquals(image, dto.getImage(), "缩略图必须原样下发，否则后台图片列恒为空");
    }

    @Test
    @DisplayName("image 为空时不回落到 galleryImage，保留「未上传缩略图」这一事实")
    void adminListDoesNotFallbackImageToGallery() {
        String gallery = "/api/v1/files/public/detail.jpg";
        AdminProductDTO dto = singleRow(productWithImages(null, gallery));

        assertNull(dto.getImage(), "image 不得被 galleryImage 顶替，否则看不出缩略图缺失");
        assertEquals(gallery, dto.getGalleryImage(), "galleryImage 仍应正常下发");
    }

    @Test
    @DisplayName("galleryImage 为空时由 image 顶替，维持原有单图商品的兼容口径")
    void adminListFallsBackGalleryToImage() {
        String image = "/api/v1/files/public/thumb.jpg";
        AdminProductDTO dto = singleRow(productWithImages(image, null));

        assertEquals(image, dto.getImage(), "缩略图应正常下发");
        assertEquals(image, dto.getGalleryImage(), "单图商品详情大图应复用缩略图");
    }
}
