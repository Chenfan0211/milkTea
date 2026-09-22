package com.wuling.trade.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.trade.entity.Order;
import com.wuling.trade.mapper.OrderMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 交易服务的内部查询接口（第 7 期）。
 *
 * <p>背景：finance 的 {@code RoleWorkbenchService} 需要按门店/渠道统计订单
 * （今日订单数、门店订单列表、渠道订单列表）。trade 拆出后这些表不再可直连，
 * 因此由 trade 暴露只读接口供其调用。
 *
 * <p><b>安全约束</b>：{@code /internal/**} 不在网关路由范围内，
 * 且各服务仅监听 127.0.0.1，不会经公网暴露。接口只读。
 *
 * <p>返回结构刻意用 Map 而非 Order 实体：避免把内部实体结构固化成
 * 跨服务契约（调用方只需其中少数字段）。
 */
@RestController
@RequestMapping("/internal")
public class TradeInternalQueryController {

    private final OrderMapper orderMapper;

    public TradeInternalQueryController(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    /**
     * 统计门店今日订单数（供工作台概览）。
     *
     * @return { "count": n }
     */
    @GetMapping("/store-today-orders")
    public Map<String, Object> storeTodayOrders(@RequestParam Long storeSubjectId) {
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        Long count = orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                .eq(Order::getStoreSubjectId, storeSubjectId)
                .ge(Order::getCreateTime, dayStart));
        return Map.of("count", count == null ? 0L : count);
    }

    /**
     * 查询门店订单列表（供工作台/渠道视图）。
     *
     * @param storeSubjectId 门店主体 ID
     * @return 订单摘要列表
     */
    @GetMapping("/store-orders")
    public List<Map<String, Object>> storeOrders(@RequestParam Long storeSubjectId) {
        return toSummaries(orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStoreSubjectId, storeSubjectId)
                .orderByDesc(Order::getId)));
    }

    /**
     * 按门店集合查询订单（渠道视角）。
     *
     * @param storeSubjectIds 逗号分隔的门店主体 ID
     */
    @GetMapping("/orders-by-stores")
    public List<Map<String, Object>> ordersByStores(@RequestParam String storeSubjectIds) {
        List<Long> ids = new ArrayList<>();
        for (String s : storeSubjectIds.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                try {
                    ids.add(Long.valueOf(t));
                } catch (NumberFormatException ignored) {
                    // 忽略非法项，避免一个坏参数导致整体失败
                }
            }
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        return toSummaries(orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .in(Order::getStoreSubjectId, ids)
                .orderByDesc(Order::getId)));
    }

    /** 只暴露调用方需要的字段，避免固化内部实体结构 */
    private List<Map<String, Object>> toSummaries(List<Order> orders) {
        return orders.stream().map(o -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", o.getId());
            m.put("orderNo", o.getOrderNo());
            m.put("storeSubjectId", o.getStoreSubjectId());
            m.put("userId", o.getUserId());
            m.put("status", o.getStatus());
            m.put("payStatus", o.getPayStatus());
            m.put("paidAmount", o.getPaidAmount());
            m.put("createTime", o.getCreateTime() == null ? null : o.getCreateTime().toString());
            return m;
        }).collect(Collectors.toList());
    }
}
