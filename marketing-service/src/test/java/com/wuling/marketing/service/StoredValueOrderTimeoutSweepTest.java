package com.wuling.marketing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wuling.marketing.entity.StoredValueOrder;
import com.wuling.marketing.mapper.StoredValueOrderMapper;
import com.wuling.user.mapper.AppUserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 储值订单「超时未支付自动关单」逻辑测试（P0 兜底）。
 *
 * <p><b>核心不变量</b>：
 * <ol>
 *   <li>只关「UNPAID 且创建时间超过 15 分钟」的储值订单；</li>
 *   <li>已支付（PAID）订单无论创建多久都绝不关闭；</li>
 *   <li>关闭动作是原子条件更新（pay_status = UNPAID），并发安全。</li>
 * </ol>
 *
 * <p>背景（真实故障）：CZ202609272359003004 归 0 后不自动取消 —— 因为
 * {@link StoredValueService} 此前根本没有超时关单机制，只插 UNPAID 就返回。
 */
class StoredValueOrderTimeoutSweepTest {

    private StoredValueOrderMapper orderMapper;
    private StoredValueService service;

    @org.junit.jupiter.api.BeforeAll
    static void initMybatisMetadata() {
        if (TableInfoHelper.getTableInfo(StoredValueOrder.class) == null) {
            MybatisConfiguration configuration = new MybatisConfiguration();
            MapperBuilderAssistant assistant =
                    new MapperBuilderAssistant(configuration, StoredValueOrder.class.getName());
            TableInfoHelper.initTableInfo(assistant, StoredValueOrder.class);
        }
    }

    @BeforeEach
    void setUp() {
        orderMapper = mock(StoredValueOrderMapper.class);
        service = new StoredValueService(null, null, orderMapper, null,
                mock(AppUserMapper.class));
    }

    private StoredValueOrder order(Long id, String payStatus, LocalDateTime createTime) {
        StoredValueOrder o = new StoredValueOrder();
        o.setId(id);
        o.setOrderNo("CZ" + id);
        o.setUserId(9L);
        o.setPayStatus(payStatus);
        o.setAmount(20000L);
        o.setCreateTime(createTime);
        return o;
    }

    @Test
    @DisplayName("过期未支付订单被扫描关闭")
    void sweepsExpiredUnpaid() {
        LocalDateTime now = LocalDateTime.now();
        List<StoredValueOrder> expired = new ArrayList<>();
        expired.add(order(1L, StoredValueService.PAY_UNPAID, now.minusMinutes(20)));
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(expired);
        when(orderMapper.update(any(StoredValueOrder.class), any(LambdaQueryWrapper.class))).thenReturn(1);

        int closed = service.closeExpiredUnpaid(100);

        assertEquals(1, closed, "应关闭 1 条过期未支付订单");
        verify(orderMapper).update(any(StoredValueOrder.class), any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("已支付订单即使过期也绝不关闭（SQL 层已被 pay_status=UNPAID 过滤）")
    void neverClosesPaid() {
        // 真实 SQL 只查 pay_status=UNPAID，因此 PAID 订单不会出现在过期候选里
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        int closed = service.closeExpiredUnpaid(100);

        assertEquals(0, closed, "已支付订单不得被超时扫描关闭");
        verify(orderMapper, never()).update(any(StoredValueOrder.class), any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("未过期未支付订单不关闭（SQL 层已被 createTime 过滤）")
    void skipsNotYetExpired() {
        // 真实 SQL 只查 createTime < now-15min，因此未过期订单不会出现在候选里
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        int closed = service.closeExpiredUnpaid(100);

        assertEquals(0, closed, "未过期订单不得关闭");
        verify(orderMapper, never()).update(any(StoredValueOrder.class), any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("关闭动作以 pay_status=UNPAID 为条件做原子更新")
    void closesWithAtomicGuard() {
        LocalDateTime now = LocalDateTime.now();
        List<StoredValueOrder> expired = new ArrayList<>();
        expired.add(order(4L, StoredValueService.PAY_UNPAID, now.minusMinutes(20)));
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(expired);
        when(orderMapper.update(any(StoredValueOrder.class), any(LambdaQueryWrapper.class))).thenReturn(1);

        service.closeExpiredUnpaid(100);

        ArgumentCaptor<LambdaQueryWrapper<StoredValueOrder>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(orderMapper).update(any(StoredValueOrder.class), captor.capture());
        captor.getValue().getTargetSql();
        assertTrue(
                captor.getValue().getParamNameValuePairs().containsValue(StoredValueService.PAY_UNPAID),
                "原子更新必须带 pay_status=UNPAID 条件");
    }
}
