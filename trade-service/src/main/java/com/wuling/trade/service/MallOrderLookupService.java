package com.wuling.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.trade.dto.OrderDTO;
import com.wuling.trade.entity.Order;
import com.wuling.trade.entity.OrderItem;
import com.wuling.trade.mapper.OrderItemMapper;
import com.wuling.trade.mapper.OrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 以独立事务读取已提交的幂等下单结果，避免主事务唯一键异常后不可见。 */
@Service
public class MallOrderLookupService {
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderService orderService;

    public MallOrderLookupService(OrderMapper orderMapper, OrderItemMapper orderItemMapper,
                                  OrderService orderService) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.orderService = orderService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public OrderDTO find(Long userId, String clientRequestId) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .eq(Order::getClientRequestId, clientRequestId)
                .last("limit 1"));
        if (order == null) return null;
        List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, order.getId()).orderByAsc(OrderItem::getId));
        return orderService.toDTO(order, items);
    }
}
