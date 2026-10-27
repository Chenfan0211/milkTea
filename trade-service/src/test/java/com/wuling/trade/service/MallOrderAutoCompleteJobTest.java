package com.wuling.trade.service;

import com.wuling.trade.entity.Order;
import com.wuling.trade.mapper.OrderMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MallOrderAutoCompleteJobTest {
    @Test
    void completesOnlyDueShippedMallOrders() {
        OrderMapper mapper = mock(OrderMapper.class);
        MallOrderService service = mock(MallOrderService.class);
        Order order = new Order();
        order.setOrderNo("MO001");
        when(mapper.selectList(any())).thenReturn(List.of(order));
        MallOrderAutoCompleteJob job = new MallOrderAutoCompleteJob(mapper, service, true, 200,
                () -> LocalDateTime.of(2026, 10, 25, 12, 0));

        int completed = job.sweep();

        assertThat(completed).isEqualTo(1);
        verify(service).completeBySystem("MO001");
    }
}
