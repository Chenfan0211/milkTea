package com.wuling.trade.internal;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wuling.trade.entity.Order;
import com.wuling.trade.mapper.OrderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「前方N杯制作中」口径回归测试。
 *
 * <p>历史事故：老实现按 status=COMPLETED 统计「已核销未取餐」，但奶茶核销即取餐、
 * complete_time 永远为 NULL，导致把历史全部已核销订单当成在制
 * （星沙乐运魔方店因此显示「前方28杯」）。
 * 下面的回归用例锁定：COMPLETED 订单不得以任何形式进入计数。
 */
class TradeInternalQueryControllerTest {

    @BeforeAll
    static void initMybatisPlusMetadata() {
        if (TableInfoHelper.getTableInfo(Order.class) == null) {
            MybatisConfiguration configuration = new MybatisConfiguration();
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, Order.class.getName());
            TableInfoHelper.initTableInfo(assistant, Order.class);
        }
    }

    @Test
    void queueCountUsesRecentPaidWindow() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        TradeInternalQueryController controller = new TradeInternalQueryController(orderMapper);
        when(orderMapper.sumRecentPendingVerifyQuantity(eq(9L), any())).thenReturn(3L);

        LocalDateTime before = LocalDateTime.now().minusHours(1).minusSeconds(5);

        assertEquals(3L, controller.storeQueueCount(9L).get("count"));

        ArgumentCaptor<LocalDateTime> windowCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(orderMapper).sumRecentPendingVerifyQuantity(eq(9L), windowCaptor.capture());
        LocalDateTime windowStart = windowCaptor.getValue();
        assertNotNull(windowStart);
        // 窗口必须是「近 1 小时」的滑动窗口，而不是自然日 0 点
        assertTrue(windowStart.isAfter(before), "窗口起点应约为 now()-1h");
        assertTrue(windowStart.isBefore(LocalDateTime.now()));
        assertEquals(0, windowStart.getMinute() == 0 && windowStart.getHour() == 0 ? 1 : 0,
                "窗口起点不应是当日零点");
    }

    @Test
    void queueCountNeverQueriesCompletedOrders() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        TradeInternalQueryController controller = new TradeInternalQueryController(orderMapper);
        when(orderMapper.sumRecentPendingVerifyQuantity(eq(9L), any())).thenReturn(0L);

        assertEquals(0L, controller.storeQueueCount(9L).get("count"));
        // 老实现用 selectList + COMPLETED 条件；修复后不得再走该路径
        verify(orderMapper, never()).selectList(any());
    }

    @Test
    void queueCountFallsBackToZeroWhenNull() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        TradeInternalQueryController controller = new TradeInternalQueryController(orderMapper);
        when(orderMapper.sumRecentPendingVerifyQuantity(eq(9L), any())).thenReturn(null);

        assertEquals(0L, controller.storeQueueCount(9L).get("count"));
    }

    /**
     * 批量接口：一次调用取回多门店队列数，未出现在结果中的门店不返回（调用方按 0 处理）。
     * 锁定「门店列表不再逐店调用」这一性能修复的口径基线。
     */
    @Test
    @SuppressWarnings("unchecked")
    void batchQueueCountReturnsPerStoreCounts() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        TradeInternalQueryController controller = new TradeInternalQueryController(orderMapper);
        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
        Map<String, Object> row1 = new HashMap<>();
        row1.put("storeSubjectId", 9L);
        row1.put("qty", 3L);
        rows.add(row1);
        Map<String, Object> row2 = new HashMap<>();
        row2.put("storeSubjectId", 10L);
        row2.put("qty", 5L);
        rows.add(row2);
        when(orderMapper.sumRecentPendingVerifyQuantities(any(), any())).thenReturn(rows);

        Map<String, Object> result = controller.storeQueueCounts("9,10,bad,11");
        Map<String, Object> counts = (Map<String, Object>) result.get("counts");

        assertEquals(3L, counts.get("9"));
        assertEquals(5L, counts.get("10"));
        // 11 无订单、bad 非法：都不应出现，调用方按 0 处理
        assertEquals(2, counts.size());

        // 只允许一次批量查询，不得逐店调用单店方法
        verify(orderMapper, never()).sumRecentPendingVerifyQuantity(any(), any());
        verify(orderMapper, org.mockito.Mockito.times(1)).sumRecentPendingVerifyQuantities(any(), any());
    }
}
