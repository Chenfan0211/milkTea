package com.wuling.trade.port;

import com.wuling.product.entity.MallProduct;
import com.wuling.product.entity.MallSku;
import com.wuling.product.entity.MallStockLog;
import com.wuling.product.internal.MallStockInternalController;
import com.wuling.product.mapper.MallProductMapper;
import com.wuling.product.mapper.MallSkuMapper;
import com.wuling.product.mapper.MallStockLogMapper;
import com.wuling.product.service.MallInventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockMvcClientHttpRequestFactory;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 真实 trade adapter → HTTP JSON → product controller → inventory 的退款契约。
 * 只将持久层替换为有状态内存账本；库存响应由真实商品服务计算。
 */
class RemoteMallCatalogInventoryContractTest {

    private static final String ORDER = "MO-CONTRACT-1";
    private final Map<Long, MallSku> skus = new LinkedHashMap<>();
    private final List<MallStockLog> logs = new ArrayList<>();
    private RemoteMallCatalogAdapter adapter;
    private boolean hideCommittedRestoreFromSnapshot;

    @BeforeEach
    void setUp() {
        skus.put(1L, sku(1L, 10));
        skus.put(2L, sku(2L, 20));
        MallSkuMapper skuMapper = mock(MallSkuMapper.class, invocation -> {
            String method = invocation.getMethod().getName();
            Long id = invocation.getArgument(0);
            MallSku sku = skus.get(id);
            if (method.startsWith("selectByIdForUpdate")) {
                return sku != null && (method.endsWith("IncludingDeleted")
                        || !Integer.valueOf(1).equals(sku.getDeleted())) ? sku : null;
            }
            if (method.startsWith("updateStockState")) {
                if (sku == null || (!method.endsWith("IncludingDeleted")
                        && Integer.valueOf(1).equals(sku.getDeleted()))) return 0;
                sku.setStock(invocation.getArgument(1));
                sku.setLockedStock(invocation.getArgument(2));
                return 1;
            }
            throw new UnsupportedOperationException(method);
        });
        MallProduct product = new MallProduct();
        product.setId(11L);
        product.setStatus("ON_SALE");
        MallProductMapper productMapper = mock(MallProductMapper.class, invocation -> {
            if ("selectBatchIds".equals(invocation.getMethod().getName())) return List.of(product);
            throw new UnsupportedOperationException(invocation.getMethod().getName());
        });
        MallStockLogMapper logMapper = mock(MallStockLogMapper.class, invocation -> {
            return switch (invocation.getMethod().getName()) {
                case "existsBizChange" -> logs.stream()
                        .filter(log -> !hideCommittedRestoreFromSnapshot || !"RESTORE".equals(log.getChangeType()))
                        .anyMatch(log ->
                        Objects.equals(log.getBizType(), invocation.getArgument(0))
                                && Objects.equals(log.getBizNo(), invocation.getArgument(1))
                                && Objects.equals(log.getChangeType(), invocation.getArgument(2)));
                case "selectRefundChangesForUpdate" -> logs.stream()
                        .filter(log -> Objects.equals(log.getOrderNo(), invocation.getArgument(0)))
                        .filter(log -> List.of("DEDUCT", "RESTORE").contains(log.getChangeType()))
                        .toList();
                case "insert" -> {
                    logs.add(invocation.getArgument(0));
                    yield 1;
                }
                default -> throw new UnsupportedOperationException(invocation.getMethod().getName());
            };
        });
        MallInventoryService inventory = new MallInventoryService(skuMapper, productMapper, logMapper);
        var controller = new MallStockInternalController(null, inventory);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        RestClient client = RestClient.builder().baseUrl("http://product-service")
                .requestFactory(new MockMvcClientHttpRequestFactory(mvc)).build();
        adapter = new RemoteMallCatalogAdapter(client);
    }

    @Test
    void fullRefundRestoresPaidStockWithOrderIdentityAndRetryIsIdempotent() {
        List<MallCatalogPort.StockLine> lines = List.of(line(1L, 3), line(2L, 2));
        pay(lines);

        assertTrue(adapter.restore(ORDER, lines).success());
        assertTrue(adapter.restore(ORDER, lines).success());

        assertStock(1L, 10, 0);
        assertStock(2L, 20, 0);
        assertEquals(2, restores().size());
        assertTrue(restores().stream().allMatch(log ->
                "ORDER".equals(log.getBizType()) && ORDER.equals(log.getBizNo())));
    }

    @Test
    void twoPartialRefundsUseDifferentAfterSaleKeysAndSameOriginalPayment() {
        pay(List.of(line(1L, 3)));

        assertTrue(adapter.restore(ORDER, "AS-1", List.of(line(1L, 1))).success());
        assertTrue(adapter.restore(ORDER, "AS-2", List.of(line(1L, 2))).success());
        assertTrue(adapter.restore(ORDER, "AS-1", List.of(line(1L, 1))).success());
        assertTrue(adapter.restore(ORDER, "AS-2", List.of(line(1L, 2))).success());

        assertStock(1L, 10, 0);
        assertEquals(List.of("AS-1", "AS-2"), restores().stream().map(MallStockLog::getBizNo).toList());
        assertTrue(restores().stream().allMatch(log -> "AFTER_SALE".equals(log.getBizType())));
    }

    @Test
    void retryRechecksCurrentLogsAfterSkuLockEvenIfSnapshotMissesCommittedRestore() {
        pay(List.of(line(1L, 3)));
        assertTrue(adapter.restore(ORDER, "AS-RETRY", List.of(line(1L, 3))).success());
        // 模拟 RR：事务最初的非锁定查询看不到另一事务刚提交的 RESTORE。
        hideCommittedRestoreFromSnapshot = true;

        assertTrue(adapter.restore(ORDER, "AS-RETRY", List.of(line(1L, 3))).success());

        assertStock(1L, 10, 0);
        assertEquals(1, restores().size());
    }

    @Test
    void reservationWithoutPaymentCannotRestoreStock() {
        assertTrue(adapter.reserve(ORDER, List.of(line(1L, 3))).success());

        assertFalse(adapter.restore(ORDER, List.of(line(1L, 3))).success());
        assertFalse(adapter.restore(ORDER, "AS-UNPAID", List.of(line(1L, 1))).success());

        assertStock(1L, 10, 3);
        assertTrue(restores().isEmpty());
    }

    @Test
    void partialRefundsCannotCumulativelyExceedOriginalDeduction() {
        pay(List.of(line(1L, 3)));
        assertTrue(adapter.restore(ORDER, "AS-1", List.of(line(1L, 2))).success());

        assertFalse(adapter.restore(ORDER, "AS-2", List.of(line(1L, 2))).success());

        assertStock(1L, 9, 0);
        assertEquals(1, restores().size());
    }

    @Test
    void fullRefundCannotRestoreQuantitiesAlreadyReturnedByPartialRefund() {
        pay(List.of(line(1L, 3)));
        assertTrue(adapter.restore(ORDER, "AS-1", List.of(line(1L, 1))).success());

        assertFalse(adapter.restore(ORDER, List.of(line(1L, 3))).success());
        assertTrue(adapter.restore(ORDER, List.of(line(1L, 2))).success());
        assertTrue(adapter.restore(ORDER, "AS-1", List.of(line(1L, 1))).success());

        assertStock(1L, 10, 0);
        assertEquals(2, restores().size());
    }

    @Test
    void aPaidOrderCannotRestoreAnUnpurchasedSku() {
        pay(List.of(line(1L, 3)));

        assertFalse(adapter.restore(ORDER, List.of(line(2L, 1))).success());

        assertStock(1L, 7, 0);
        assertStock(2L, 20, 0);
        assertTrue(restores().isEmpty());
    }

    @Test
    void allSkuLimitsAreCheckedBeforeAnyStockIsChanged() {
        pay(List.of(line(1L, 3), line(2L, 2)));

        assertFalse(adapter.restore(ORDER, List.of(line(1L, 1), line(2L, 3))).success());

        assertStock(1L, 7, 0);
        assertStock(2L, 18, 0);
        assertTrue(restores().isEmpty());
    }

    @Test
    void aDeletedSkuStillReceivesItsPaidRefundStock() {
        pay(List.of(line(1L, 3)));
        skus.get(1L).setDeleted(1);

        assertTrue(adapter.restore(ORDER, "AS-DELETED", List.of(line(1L, 3))).success());

        assertStock(1L, 10, 0);
        assertEquals(1, restores().size());
    }

    private void pay(List<MallCatalogPort.StockLine> lines) {
        assertTrue(adapter.reserve(ORDER, lines).success());
        assertTrue(adapter.confirm(ORDER, lines).success());
    }

    private List<MallStockLog> restores() {
        return logs.stream().filter(log -> "RESTORE".equals(log.getChangeType())).toList();
    }

    private void assertStock(Long skuId, int stock, int locked) {
        assertEquals(stock, skus.get(skuId).getStock());
        assertEquals(locked, skus.get(skuId).getLockedStock());
    }

    private static MallCatalogPort.StockLine line(Long skuId, int quantity) {
        return new MallCatalogPort.StockLine(skuId, quantity);
    }

    private static MallSku sku(Long id, int stock) {
        MallSku sku = new MallSku();
        sku.setId(id);
        sku.setProductId(11L);
        sku.setStock(stock);
        sku.setLockedStock(0);
        sku.setDeleted(0);
        return sku;
    }
}
