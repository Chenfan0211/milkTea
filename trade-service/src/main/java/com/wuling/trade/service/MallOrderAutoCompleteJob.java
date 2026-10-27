package com.wuling.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.trade.entity.Order;
import com.wuling.trade.mapper.OrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

/** 商城发货后 15 天自动确认收货。 */
@Component
public class MallOrderAutoCompleteJob {
    private static final Logger log = LoggerFactory.getLogger(MallOrderAutoCompleteJob.class);

    private final OrderMapper orderMapper;
    private final MallOrderService mallOrderService;
    private final boolean enabled;
    private final int batchSize;
    private final Supplier<LocalDateTime> clock;

    @Autowired
    public MallOrderAutoCompleteJob(OrderMapper orderMapper, MallOrderService mallOrderService,
                                    @Value("${app.mall.auto-complete.enabled:true}") boolean enabled,
                                    @Value("${app.mall.auto-complete.batch-size:200}") int batchSize) {
        this(orderMapper, mallOrderService, enabled, batchSize, LocalDateTime::now);
    }

    MallOrderAutoCompleteJob(OrderMapper orderMapper, MallOrderService mallOrderService,
                             boolean enabled, int batchSize, Supplier<LocalDateTime> clock) {
        this.orderMapper = orderMapper;
        this.mallOrderService = mallOrderService;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.mall.auto-complete.cron:0 * * * * ?}")
    public void run() {
        sweep();
    }

    public int sweep() {
        if (!enabled) return 0;
        List<Order> due = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderType, "MALL")
                .eq(Order::getStatus, OrderService.STATUS_SHIPPED)
                .le(Order::getAutoCompleteTime, clock.get())
                .orderByAsc(Order::getAutoCompleteTime)
                .last("limit " + Math.max(1, batchSize)));
        int completed = 0;
        for (Order order : due) {
            try {
                mallOrderService.completeBySystem(order.getOrderNo());
                completed++;
            } catch (Exception e) {
                log.warn("商城自动完成失败 orderNo={}", order.getOrderNo(), e);
            }
        }
        return completed;
    }
}
