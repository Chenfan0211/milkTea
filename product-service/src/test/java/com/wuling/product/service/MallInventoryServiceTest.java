package com.wuling.product.service;

import com.wuling.product.dto.MallStockItemRequest;
import com.wuling.product.dto.MallStockMutationRequest;
import com.wuling.product.dto.MallStockMutationResponse;
import com.wuling.product.entity.MallProduct;
import com.wuling.product.entity.MallSku;
import com.wuling.product.mapper.MallProductMapper;
import com.wuling.product.mapper.MallSkuMapper;
import com.wuling.product.entity.MallStockLog;
import com.wuling.product.mapper.MallStockLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MallInventoryServiceTest {

    private MallSkuMapper skuMapper;
    private MallProductMapper productMapper;
    private MallStockLogMapper stockLogMapper;
    private MallInventoryService service;

    @BeforeEach
    void setUp() {
        skuMapper = mock(MallSkuMapper.class);
        productMapper = mock(MallProductMapper.class);
        stockLogMapper = mock(MallStockLogMapper.class);
        when(skuMapper.updateStockState(any(Long.class), any(Integer.class), any(Integer.class))).thenReturn(1);
        service = new MallInventoryService(skuMapper, productMapper, stockLogMapper);
    }

    @Test
    @DisplayName("任一 SKU 可用库存不足时整单不预占并返回 success=false")
    void reserveReturnsFalseWithoutMutationWhenStockIsInsufficient() {
        MallSku sku = sku(1L, 2, 0);
        when(skuMapper.selectByIdForUpdate(1L)).thenReturn(sku);
        when(productMapper.selectBatchIds(any())).thenReturn(List.of(onSaleProduct(11L)));

        MallStockMutationResponse response = service.reserve(request("ORDER-1", item(1L, 3)));

        assertFalse(response.isSuccess());
        verify(skuMapper, never()).updateStockState(any(), any(), any());
        verify(stockLogMapper, never()).insert(any(MallStockLog.class));
    }

    @Test
    @DisplayName("预占按 skuId 升序加锁，避免并发订单交叉死锁")
    void reserveLocksSkusInAscendingIdOrder() {
        when(skuMapper.selectByIdForUpdate(1L)).thenReturn(sku(1L, 5, 0));
        when(skuMapper.selectByIdForUpdate(2L)).thenReturn(sku(2L, 6, 0));
        when(productMapper.selectBatchIds(any())).thenReturn(List.of(onSaleProduct(11L)));

        MallStockMutationResponse response = service.reserve(
                request("ORDER-2", item(2L, 1), item(1L, 1)));

        assertTrue(response.isSuccess());
        InOrder order = inOrder(skuMapper);
        order.verify(skuMapper).selectByIdForUpdate(1L);
        order.verify(skuMapper).selectByIdForUpdate(2L);
    }

    @Test
    @DisplayName("同一订单重复预占直接返回成功，不重复增加锁定库存")
    void reserveIsIdempotentByOrderAndChangeType() {
        when(stockLogMapper.existsBizChange("ORDER", "ORDER-3", "RESERVE")).thenReturn(true);

        MallStockMutationResponse response = service.reserve(request("ORDER-3", item(1L, 2)));

        assertTrue(response.isSuccess());
        verify(skuMapper, never()).selectByIdForUpdate(any());
        verify(skuMapper, never()).updateStockState(any(), any(), any());
    }

    @Test
    @DisplayName("支付确认重复调用按 DEDUCT 幂等")
    void confirmIsIdempotent() {
        when(stockLogMapper.existsBizChange("ORDER", "ORDER-4", "DEDUCT")).thenReturn(true);

        assertTrue(service.confirm(request("ORDER-4", item(1L, 2))).isSuccess());
        verify(skuMapper, never()).updateStockState(any(), any(), any());
    }

    @Test
    @DisplayName("未支付取消重复调用按 RELEASE 幂等")
    void releaseIsIdempotent() {
        when(stockLogMapper.existsBizChange("ORDER", "ORDER-5", "RELEASE")).thenReturn(true);

        assertTrue(service.release(request("ORDER-5", item(1L, 2))).isSuccess());
        verify(skuMapper, never()).updateStockState(any(), any(), any());
    }

    @Test
    @DisplayName("未支付取消可将远程扣减悬挂原额补回")
    void releaseRestoresOrphanDeduction() {
        when(stockLogMapper.existsBizChange("ORDER", "ORDER-DEDUCT", "DEDUCT")).thenReturn(true);
        when(skuMapper.selectByIdForUpdate(1L)).thenReturn(sku(1L, 6, 0));

        assertTrue(service.release(request("ORDER-DEDUCT", item(1L, 2))).isSuccess());

        verify(skuMapper).updateStockState(1L, 8, 0);
        MallStockLog log = captureSingleLog();
        assertEquals("RESTORE", log.getChangeType());
        assertEquals(2, log.getStockDelta());
    }

    @Test
    @DisplayName("退款回补重复调用按 RESTORE 幂等")
    void restoreIsIdempotent() {
        when(stockLogMapper.existsBizChange("ORDER", "ORDER-6", "RESTORE")).thenReturn(true);

        assertTrue(service.restore(request("ORDER-6", item(1L, 2))).isSuccess());
        verify(skuMapper, never()).updateStockState(any(), any(), any());
    }

    @Test
    @DisplayName("支付确认同时扣减 stock 与 lockedStock")
    void confirmDeductsStockAndLockedStockTogether() {
        MallSku sku = sku(1L, 8, 3);
        when(skuMapper.selectByIdForUpdate(1L)).thenReturn(sku);
        when(stockLogMapper.existsBizChange("ORDER", "ORDER-7", "RESERVE")).thenReturn(true);

        assertTrue(service.confirm(request("ORDER-7", item(1L, 2))).isSuccess());

        verify(skuMapper).updateStockState(eq(1L), eq(6), eq(1));
        verify(stockLogMapper).insert(any(MallStockLog.class));
    }

    @Test
    @DisplayName("预占成功写 RESERVE 库存流水")
    void reserveWritesStockLog() {
        when(skuMapper.selectByIdForUpdate(1L)).thenReturn(sku(1L, 8, 0));
        when(productMapper.selectBatchIds(any())).thenReturn(List.of(onSaleProduct(11L)));

        service.reserve(request("ORDER-8", item(1L, 2)));

        MallStockLog log = captureSingleLog();
        assertEquals("RESERVE", log.getChangeType());
        assertEquals(0, log.getStockDelta());
        assertEquals(2, log.getLockedDelta());
        assertEquals(2, log.getLockedAfter());
    }

    @Test
    @DisplayName("释放成功写 RELEASE 库存流水")
    void releaseWritesStockLog() {
        when(skuMapper.selectByIdForUpdate(1L)).thenReturn(sku(1L, 8, 2));
        when(stockLogMapper.existsBizChange("ORDER", "ORDER-9", "RESERVE")).thenReturn(true);

        assertTrue(service.release(request("ORDER-9", item(1L, 2))).isSuccess());

        verify(skuMapper).updateStockState(1L, 8, 0);
        MallStockLog log = captureSingleLog();
        assertEquals("RELEASE", log.getChangeType());
        assertEquals(-2, log.getLockedDelta());
    }

    @Test
    @DisplayName("退款回补成功写 RESTORE 库存流水")
    void restoreWritesStockLog() {
        when(skuMapper.selectByIdForUpdateIncludingDeleted(1L)).thenReturn(sku(1L, 8, 0));
        when(stockLogMapper.existsBizChange("ORDER", "ORDER-10", "RESTORE")).thenReturn(false);
        when(stockLogMapper.existsBizChange("ORDER", "ORDER-10", "DEDUCT")).thenReturn(true);

        assertTrue(service.restore(request("ORDER-10", item(1L, 2))).isSuccess());

        verify(skuMapper).updateStockStateIncludingDeleted(1L, 10, 0);
        MallStockLog log = captureSingleLog();
        assertEquals("RESTORE", log.getChangeType());
        assertEquals(2, log.getStockDelta());
    }

    private MallStockLog captureSingleLog() {
        ArgumentCaptor<MallStockLog> captor = ArgumentCaptor.forClass(MallStockLog.class);
        verify(stockLogMapper).insert(captor.capture());
        return captor.getValue();
    }
    private static MallStockMutationRequest request(String orderNo, MallStockItemRequest... items) {
        MallStockMutationRequest request = new MallStockMutationRequest();
        request.setOrderNo(orderNo);
        request.setItems(List.of(items));
        return request;
    }

    private static MallStockItemRequest item(Long skuId, Integer quantity) {
        MallStockItemRequest item = new MallStockItemRequest();
        item.setSkuId(skuId);
        item.setQuantity(quantity);
        return item;
    }

    private static MallSku sku(Long id, Integer stock, Integer locked) {
        MallSku sku = new MallSku();
        sku.setId(id);
        sku.setProductId(11L);
        sku.setSupplierSubjectId(401L);
        sku.setSkuCode("SKU-" + id);
        sku.setSpecValue("默认");
        sku.setSalePrice(1000L);
        sku.setOriginalPrice(1200L);
        sku.setCostPrice(500L);
        sku.setPlatformCommission(100L);
        sku.setStock(stock);
        sku.setLockedStock(locked);
        sku.setVersion(0);
        return sku;
    }

    private static MallProduct onSaleProduct(Long id) {
        MallProduct product = new MallProduct();
        product.setId(id);
        product.setStatus("ON_SALE");
        product.setAttributionStoreSubjectId(101L);
        return product;
    }
}








