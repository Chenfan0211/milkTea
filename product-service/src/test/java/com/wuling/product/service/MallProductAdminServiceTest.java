package com.wuling.product.service;

import com.wuling.product.dto.MallProductSaveRequest;
import com.wuling.product.dto.MallSkuAdminDTO;
import com.wuling.product.dto.MallStockAdjustRequest;
import com.wuling.product.entity.MallCategory;
import com.wuling.product.entity.MallProduct;
import com.wuling.product.entity.MallSku;
import com.wuling.product.entity.MallStockLog;
import com.wuling.product.mapper.MallCategoryMapper;
import com.wuling.product.mapper.MallProductMapper;
import com.wuling.product.mapper.MallSkuMapper;
import com.wuling.product.mapper.MallStockLogMapper;
import com.wuling.product.port.SubjectQueryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MallProductAdminServiceTest {

    private MallProductMapper productMapper;
    private MallSkuMapper skuMapper;
    private MallCategoryMapper categoryMapper;
    private MallStockLogMapper stockLogMapper;
    private SubjectQueryPort subjectQueryPort;
    private MallProductAdminService service;

    @BeforeEach
    void setUp() {
        productMapper = mock(MallProductMapper.class);
        skuMapper = mock(MallSkuMapper.class);
        categoryMapper = mock(MallCategoryMapper.class);
        stockLogMapper = mock(MallStockLogMapper.class);
        subjectQueryPort = mock(SubjectQueryPort.class);
        service = new MallProductAdminService(
                productMapper, skuMapper, categoryMapper, stockLogMapper, subjectQueryPort);
    }

    @Test
    @DisplayName("商品上架前至少需要一个 SKU")
    void cannotPutOnSaleWithoutSku() {
        when(productMapper.selectById(1L)).thenReturn(product(1L));
        when(skuMapper.selectList(any())).thenReturn(List.of());
        when(subjectQueryPort.findById(101L)).thenReturn(subject(101L, "STORE"));

        assertThrows(com.wuling.common.exception.BusinessException.class,
                () -> service.updateStatus(1L, "ON_SALE"));
    }

    @Test
    @DisplayName("SKU 缺少供应商时禁止上架")
    void cannotPutOnSaleWhenSupplierIsMissing() {
        when(productMapper.selectById(1L)).thenReturn(product(1L));
        MallSku sku = sku(1L, 5, 0);
        sku.setSupplierSubjectId(null);
        when(skuMapper.selectList(any())).thenReturn(List.of(sku));
        when(subjectQueryPort.findById(101L)).thenReturn(subject(101L, "STORE"));

        assertThrows(com.wuling.common.exception.BusinessException.class,
                () -> service.updateStatus(1L, "ON_SALE"));
    }

    @Test
    @DisplayName("编辑在售商品未传状态时保持在售，不误降为草稿")
    void updateWithoutStatusKeepsCurrentStatus() {
        MallProduct current = product(1L);
        current.setStatus("ON_SALE");
        when(productMapper.selectById(1L)).thenReturn(current);
        when(productMapper.selectByProductCodeIncludingDeleted("P-1")).thenReturn(current);
        when(skuMapper.selectList(any())).thenReturn(List.of(sku(1L, 5, 0)));
        when(categoryMapper.selectById(5L)).thenReturn(category());
        when(subjectQueryPort.findById(101L)).thenReturn(subject(101L, "STORE"));
        when(subjectQueryPort.findById(401L)).thenReturn(subject(401L, "SUPPLIER"));
        when(productMapper.selectById(1L)).thenReturn(current);

        MallProductSaveRequest request = productRequest();
        request.setStatus(null);

        service.update(1L, request);

        assertEquals("ON_SALE", current.getStatus());
        verify(productMapper).updateById(current);
    }

    @Test
    @DisplayName("后台库存调整低于已锁定量时拒绝且不写流水")
    void adjustStockRejectsStockBelowLockedStock() {
        MallSku sku = sku(1L, 5, 4);
        when(skuMapper.selectByIdForUpdate(1L)).thenReturn(sku);

        MallStockAdjustRequest request = new MallStockAdjustRequest();
        request.setStock(3);
        request.setRemark("盘点");

        assertThrows(com.wuling.common.exception.BusinessException.class,
                () -> service.adjustStock(1L, 1L, request, "99"));
        verify(skuMapper, never()).updateStockState(any(), any(), any());
        verify(stockLogMapper, never()).insert(any(MallStockLog.class));
    }

    @Test
    @DisplayName("后台调整库存成功后写 ADJUST 审计流水")
    void adjustStockWritesAuditLog() {
        when(skuMapper.selectByIdForUpdate(1L)).thenReturn(sku(1L, 5, 2));

        MallStockAdjustRequest request = new MallStockAdjustRequest();
        request.setStock(8);
        request.setRemark("补货");

        service.adjustStock(1L, 1L, request, "99");

        verify(skuMapper).updateStockState(1L, 8, 2);
        verify(stockLogMapper).insert(any(MallStockLog.class));
        MallStockLog auditLog = captureSingleAuditLog();
        assertEquals("ADJUST", auditLog.getChangeType());
        assertEquals("ADJUST", auditLog.getBizType());
        assertTrue(auditLog.getBizNo() != null && !auditLog.getBizNo().isBlank(),
                "库存调整也必须写入业务幂等号，否则唯一索引对其不生效");
    }

    @Test
    @DisplayName("整体保存 SKU 时拒绝重复 SKU code")
    void saveSkusRejectsDuplicateSkuCode() {
        when(productMapper.selectById(1L)).thenReturn(product(1L));
        MallSkuAdminDTO first = skuDto("SKU-DUP");
        MallSkuAdminDTO second = skuDto("SKU-DUP");

        assertThrows(com.wuling.common.exception.BusinessException.class,
                () -> service.saveSkus(1L, List.of(first, second)));
    }

    @Test
    @DisplayName("整体保存 SKU 时不能删除仍有锁定库存的 SKU")
    void saveSkusRejectsDeletingLockedSku() {
        when(productMapper.selectById(1L)).thenReturn(product(1L));
        MallSku lockedSku = sku(1L, 5, 2);
        when(skuMapper.selectList(any())).thenReturn(List.of(lockedSku));
        when(skuMapper.selectByIdForUpdate(1L)).thenReturn(lockedSku);

        assertThrows(com.wuling.common.exception.BusinessException.class,
                () -> service.saveSkus(1L, List.of()));
        verify(skuMapper).selectByIdForUpdate(1L);
        verify(skuMapper, never()).deleteById(any(Long.class));
    }

    private static MallProduct product(Long id) {
        MallProduct product = new MallProduct();
        product.setId(id);
        product.setProductCode("P-" + id);
        product.setCategoryId(5L);
        product.setName("商品" + id);
        product.setSpecName("规格");
        product.setAttributionStoreSubjectId(101L);
        product.setStatus("DRAFT");
        product.setSort(0);
        product.setSalesCount(0);
        return product;
    }

    private static MallProductSaveRequest productRequest() {
        MallProductSaveRequest request = new MallProductSaveRequest();
        request.setProductCode("P-1");
        request.setCategoryId(5L);
        request.setName("商品1");
        request.setSpecName("规格");
        request.setAttributionStoreSubjectId(101L);
        return request;
    }

    private static MallCategory category() {
        MallCategory category = new MallCategory();
        category.setId(5L);
        category.setCode("C-5");
        category.setName("分类5");
        category.setStatus("ENABLED");
        return category;
    }

    @Test
    @DisplayName("有历史库存流水的商品禁止删除，只能下架")
    void cannotDeleteProductWithHistory() {
        when(productMapper.selectById(1L)).thenReturn(product(1L));
        when(stockLogMapper.existsByProductId(1L)).thenReturn(true);

        assertThrows(com.wuling.common.exception.BusinessException.class,
                () -> service.delete(1L));
        verify(productMapper, never()).deleteById(1L);
    }

    @Test
    @DisplayName("无历史流水的商品允许删除")
    void canDeleteCleanProduct() {
        when(productMapper.selectById(1L)).thenReturn(product(1L));
        when(stockLogMapper.existsByProductId(1L)).thenReturn(false);

        service.delete(1L);

        verify(productMapper).deleteById(1L);
    }

    private static MallSku sku(Long id, Integer stock, Integer locked) {
        MallSku sku = new MallSku();
        sku.setId(id);
        sku.setProductId(1L);
        sku.setSkuCode("SKU-" + id);
        sku.setSpecValue("默认");
        sku.setSupplierSubjectId(401L);
        sku.setSalePrice(1000L);
        sku.setOriginalPrice(1200L);
        sku.setCostPrice(500L);
        sku.setPlatformCommission(100L);
        sku.setStock(stock);
        sku.setLockedStock(locked);
        sku.setSort(0);
        sku.setVersion(0);
        return sku;
    }

    private static MallSkuAdminDTO skuDto(String code) {
        MallSkuAdminDTO dto = new MallSkuAdminDTO();
        dto.setSkuCode(code);
        dto.setSpecValue("默认");
        dto.setSupplierSubjectId(401L);
        dto.setSalePrice(1000L);
        dto.setOriginalPrice(1200L);
        dto.setCostPrice(500L);
        dto.setPlatformCommission(100L);
        dto.setStock(5);
        dto.setSort(0);
        return dto;
    }

    private static SubjectQueryPort.SubjectView subject(Long id, String type) {
        SubjectQueryPort.SubjectView view = new SubjectQueryPort.SubjectView();
        view.setId(id);
        view.setSubjectType(type);
        view.setName("主体" + id);
        return view;
    }

    private MallStockLog captureSingleAuditLog() {
        org.mockito.ArgumentCaptor<MallStockLog> captor =
                org.mockito.ArgumentCaptor.forClass(MallStockLog.class);
        verify(stockLogMapper).insert(captor.capture());
        return captor.getValue();
    }

}

