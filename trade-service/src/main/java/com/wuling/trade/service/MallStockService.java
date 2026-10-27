package com.wuling.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.trade.entity.MallAfterSale;
import com.wuling.trade.entity.Order;
import com.wuling.trade.entity.OrderItem;
import com.wuling.trade.mapper.MallAfterSaleMapper;
import com.wuling.trade.mapper.OrderItemMapper;
import com.wuling.trade.port.MallCatalogPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 商城库存编排（具体幂等与扣减在 product-service）。 */
@Service
public class MallStockService {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final OrderItemMapper orderItemMapper;
    private final MallCatalogPort catalogPort;
    private final MallAfterSaleMapper afterSaleMapper;

    public MallStockService(OrderItemMapper orderItemMapper, MallCatalogPort catalogPort) {
        this(orderItemMapper, catalogPort, null);
    }

    @Autowired
    public MallStockService(OrderItemMapper orderItemMapper, MallCatalogPort catalogPort,
                            MallAfterSaleMapper afterSaleMapper) {
        this.orderItemMapper = orderItemMapper;
        this.catalogPort = catalogPort;
        this.afterSaleMapper = afterSaleMapper;
    }

    public void deductForPaid(Order order) {
        if (!isMall(order)) return;
        apply(catalogPort.confirm(order.getOrderNo(), linesOf(order)), "扣减商城库存失败");
    }

    public void releaseForCancelled(Order order) {
        if (!isMall(order)) return;
        apply(catalogPort.release(order.getOrderNo(), linesOf(order)), "释放商城库存失败");
    }

    public void restoreForRefund(Order order) {
        if (!isMall(order)) return;
        apply(catalogPort.restore(order.getOrderNo(), linesOf(order)), "回补商城库存失败");
    }

    /**
     * 回补本次售后数量，并返回订单各明细累计已退数量（含本次）。
     */
    public Map<Long, Integer> restoreForAfterSale(Order order, MallAfterSale afterSale) {
        if (!isMall(order)) return Map.of();
        Map<Long, Integer> thisQuantity = afterSaleQuantities(afterSale);
        List<MallCatalogPort.StockLine> lines = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : thisQuantity.entrySet()) {
            OrderItem item = orderItemMapper.selectById(entry.getKey());
            if (item == null || !order.getId().equals(item.getOrderId()) || item.getMallSkuId() == null) {
                throw new BusinessException(ResultCode.ERROR, "售后商品明细与原订单不一致");
            }
            lines.add(new MallCatalogPort.StockLine(item.getMallSkuId(), entry.getValue()));
        }
        if (!lines.isEmpty()) {
            // 售后单可能被重试/回调重复触发；先落本次数量再查累计，保证返回的是累计已退量。
            apply(catalogPort.restore(order.getOrderNo(), afterSale.getAfterSaleNo(), lines),
                    "回补售后库存失败");
        }
        Map<Long, Integer> totals = new LinkedHashMap<>();
        for (OrderItem item : orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, order.getId()))) {
            int cumulative = afterSaleMapper == null
                    ? thisQuantity.getOrDefault(item.getId(), 0)
                    : Math.max(thisQuantity.getOrDefault(item.getId(), 0),
                            afterSaleMapper.selectSumRefundedQuantity(order.getId(), item.getId()));
            totals.put(item.getId(), cumulative);
        }
        return totals;
    }

    private Map<Long, Integer> afterSaleQuantities(MallAfterSale afterSale) {
        Map<Long, Integer> result = new LinkedHashMap<>();
        try {
            var items = JSON.readTree(afterSale.getItems() == null ? "[]" : afterSale.getItems());
            for (var item : items) {
                long orderItemId = item.path("orderItemId").asLong(0);
                int quantity = item.path("quantity").asInt(0);
                if (orderItemId > 0 && quantity > 0) {
                    result.merge(orderItemId, quantity, Integer::sum);
                }
            }
        } catch (Exception e) {
            throw new BusinessException(ResultCode.ERROR, "售后商品快照损坏");
        }
        return result;
    }

    private void apply(MallCatalogPort.StockResult result, String fallback) {
        if (result == null || !result.success()) {
            throw new BusinessException(ResultCode.ERROR,
                    result == null || result.message() == null ? fallback : result.message());
        }
    }

    private List<MallCatalogPort.StockLine> linesOf(Order order) {
        return orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getOrderId, order.getId()).orderByAsc(OrderItem::getMallSkuId))
                .stream().filter(item -> item.getMallSkuId() != null)
                .map(item -> new MallCatalogPort.StockLine(item.getMallSkuId(),
                        item.getQuantity() == null ? 1 : item.getQuantity()))
                .toList();
    }

    private boolean isMall(Order order) {
        return order != null && "MALL".equalsIgnoreCase(order.getOrderType());
    }
}

