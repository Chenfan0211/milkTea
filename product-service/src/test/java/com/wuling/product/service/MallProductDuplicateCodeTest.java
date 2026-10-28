package com.wuling.product.service;

import com.wuling.common.exception.BusinessException;
import com.wuling.product.dto.MallProductSaveRequest;
import com.wuling.product.entity.MallCategory;
import com.wuling.product.entity.MallProduct;
import com.wuling.product.mapper.MallCategoryMapper;
import com.wuling.product.mapper.MallProductMapper;
import com.wuling.product.mapper.MallSkuMapper;
import com.wuling.product.mapper.MallStockLogMapper;
import com.wuling.product.port.SubjectQueryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MallProductDuplicateCodeTest {

    private MallProductMapper productMapper;
    private SubjectQueryPort subjectQueryPort;
    private MallCategoryMapper categoryMapper;
    private MallProductAdminService service;

    @BeforeEach
    void setUp() {
        productMapper = mock(MallProductMapper.class);
        subjectQueryPort = mock(SubjectQueryPort.class);
        categoryMapper = mock(MallCategoryMapper.class);
        service = new MallProductAdminService(
                productMapper,
                mock(MallSkuMapper.class),
                categoryMapper,
                mock(MallStockLogMapper.class),
                subjectQueryPort);
    }

    @Test
    @DisplayName("并发创建触发数据库唯一键时返回商品编码已存在")
    void duplicateProductCodeExceptionBecomesBadRequest() {
        MallProduct existing = product();
        when(productMapper.selectByProductCodeIncludingDeleted("P-1")).thenReturn(null);
        when(productMapper.selectById(1L)).thenReturn(existing);
        when(productMapper.insert(any(MallProduct.class))).thenThrow(new DuplicateKeyException("uk_mall_product_code"));
        MallCategory category = new MallCategory();
        category.setId(5L);
        SubjectQueryPort.SubjectView store = new SubjectQueryPort.SubjectView();
        store.setId(101L);
        store.setSubjectType("STORE");
        when(subjectQueryPort.findById(101L)).thenReturn(store);
        when(categoryMapper.selectById(5L)).thenReturn(category);

        MallProductSaveRequest request = request();
        request.setCategoryId(5L);

        BusinessException error = assertThrows(BusinessException.class, () -> service.create(request));
        assertEquals(400, error.getCode());
        assertEquals("商品编码已存在", error.getMessage());
    }

    @Test
    @DisplayName("并发编辑触发数据库唯一键时返回商品编码已存在")
    void duplicateProductCodeOnUpdateBecomesBadRequest() {
        MallProduct current = product();
        current.setId(1L);
        MallProduct other = product();
        other.setId(2L);
        when(productMapper.selectById(1L)).thenReturn(current);
        when(productMapper.selectByProductCodeIncludingDeleted("P-1")).thenReturn(null);
        when(productMapper.updateById(any(MallProduct.class))).thenThrow(new DuplicateKeyException("uk_mall_product_code"));
        MallCategory category = new MallCategory();
        category.setId(5L);
        SubjectQueryPort.SubjectView store = new SubjectQueryPort.SubjectView();
        store.setId(101L);
        store.setSubjectType("STORE");
        when(subjectQueryPort.findById(101L)).thenReturn(store);
        when(categoryMapper.selectById(5L)).thenReturn(category);

        MallProductSaveRequest request = request();
        request.setCategoryId(5L);

        BusinessException error = assertThrows(BusinessException.class, () -> service.update(1L, request));
        assertEquals(400, error.getCode());
        assertEquals("商品编码已存在", error.getMessage());
    }

    private static MallProduct product() {
        MallProduct product = new MallProduct();
        product.setProductCode("P-1");
        product.setCategoryId(5L);
        return product;
    }

    private static MallProductSaveRequest request() {
        MallProductSaveRequest request = new MallProductSaveRequest();
        request.setProductCode("P-1");
        request.setName("商品1");
        request.setAttributionStoreSubjectId(101L);
        return request;
    }
}
