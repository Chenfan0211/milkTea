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

/**
 * 门店订单「超时未支付自动关单」兜底定时任务（P0）。
 *
 * <p>背景：正常关单依赖 outbox → RabbitMQ → {@code OrderTimeoutConsumer} 延迟消息链路，
 * 一旦消息中间件不通 / outbox 扫描器关闭 / 消息进 DLQ，未支付订单会永远停在 CREATED。
 * 本任务每分钟扫描一次，把超过 15 分钟仍未支付的 CREATED 订单关闭，
 * 作为不依赖 MQ 的最终兜底。
 *
 * <p>复用 {@link OrderService#closeIfUnpaid(String)}：该方法已带行锁 + 幂等，
 * 已支付订单不会被误关，重复执行安全。
 */
@Component
public class OrderTimeoutSweepJob {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutSweepJob.class);

    /** 门店订单支付时限（分钟）：与 OrderService.createOrder 的 15 分钟延迟消息一致。 */
    private static final long PAYMENT_WINDOW_MINUTES = 15;

    private final OrderMapper orderMapper;
    private final OrderService orderService;
    private final boolean enabled;
    private final int batchSize;

    @Autowired
    public OrderTimeoutSweepJob(
            OrderMapper orderMapper,
            OrderService orderService,
            @Value("${app.order-timeout-scan.enabled:true}") boolean enabled,
            @Value("${app.order-timeout-scan.batch-size:200}") int batchSize) {
        this.orderMapper = orderMapper;
        this.orderService = orderService;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    /** 测试用构造：默认开启，批大小 200。 */
    public OrderTimeoutSweepJob(OrderMapper orderMapper, OrderService orderService, boolean enabled) {
        this(orderMapper, orderService, enabled, 200);
    }

    @Scheduled(cron = "${app.order-timeout-scan.cron:0 * * * * ?}")
    public void run() {
        sweep();
    }

    /** 单轮扫描，便于测试与运维手动触发。 */
    public int sweep() {
        if (!enabled) {
            return 0;
        }
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(PAYMENT_WINDOW_MINUTES);
        List<Order> expired = orderMapper.selectList(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getStatus, OrderService.STATUS_CREATED)
                        .eq(Order::getPayStatus, "UNPAID")
                        .lt(Order::getCreateTime, deadline)
                        .last("limit " + Math.max(1, batchSize)));
        int closed = 0;
        for (Order order : expired) {
            try {
                if (orderService.closeIfUnpaid(order.getOrderNo())) {
                    closed++;
                }
            } catch (Exception e) {
                log.warn("订单超时关单单条失败 orderNo={}", order.getOrderNo(), e);
            }
        }
        if (closed > 0) {
            log.warn("订单超时关单兜底任务本批关闭 {} 条", closed);
        }
        return closed;
    }
}
