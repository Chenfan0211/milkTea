package com.wuling.product.service;

import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.product.dto.MallStockItemRequest;
import com.wuling.product.dto.MallStockMutationRequest;
import com.wuling.product.dto.MallStockMutationResponse;
import com.wuling.product.entity.MallProduct;
import com.wuling.product.entity.MallSku;
import com.wuling.product.entity.MallStockLog;
import com.wuling.product.mapper.MallProductMapper;
import com.wuling.product.mapper.MallSkuMapper;
import com.wuling.product.mapper.MallStockLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 商城库存事务。
 *
 * <p>所有订单库存操作先按 skuId 升序逐行 SELECT ... FOR UPDATE，保证不同订单
 * 即使商品明细顺序不同也不会形成交叉死锁。订单级幂等以
 * mall_stock_log(order_no, change_type) 是否存在作为判定依据；锁住 SKU 后会再次
 * 检查，覆盖同一订单并发重试。
 */
@Service
public class MallInventoryService {

    public static final String RESERVE = "RESERVE";
    public static final String DEDUCT = "DEDUCT";
    public static final String RELEASE = "RELEASE";
    public static final String RESTORE = "RESTORE";

    private final MallSkuMapper skuMapper;
    private final MallProductMapper productMapper;
    private final MallStockLogMapper stockLogMapper;

    public MallInventoryService(MallSkuMapper skuMapper,
                                MallProductMapper productMapper,
                                MallStockLogMapper stockLogMapper) {
        this.skuMapper = skuMapper;
        this.productMapper = productMapper;
        this.stockLogMapper = stockLogMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public MallStockMutationResponse reserve(MallStockMutationRequest request) {
        String orderNo = validateRequest(request);
        String bizType = validateBizType(request);
        String bizNo = validateBizNo(request);
        if (stockLogMapper.existsBizChange(bizType, bizNo, RESERVE)) {
            return MallStockMutationResponse.ok();
        }
        Map<Long, Integer> items = normalizeItems(request);
        Map<Long, MallSku> skus = lockSkus(items);
        if (stockLogMapper.existsBizChange(bizType, bizNo, RESERVE)) {
            return MallStockMutationResponse.ok();
        }
        Map<Long, MallProduct> products = loadProducts(skus.values());
        for (Map.Entry<Long, Integer> entry : items.entrySet()) {
            MallSku sku = skus.get(entry.getKey());
            if (sku == null) {
                return MallStockMutationResponse.fail("SKU 不存在");
            }
            MallProduct product = products.get(sku.getProductId());
            if (product == null || !"ON_SALE".equals(product.getStatus())) {
                return MallStockMutationResponse.fail("商品已下架");
            }
            if (sku.availableStock() < entry.getValue()) {
                return MallStockMutationResponse.fail("可用库存不足");
            }
        }
        for (Map.Entry<Long, Integer> entry : items.entrySet()) {
            MallSku sku = skus.get(entry.getKey());
            int quantity = entry.getValue();
            int lockedAfter = value(sku.getLockedStock()) + quantity;
            updateState(sku.getId(), value(sku.getStock()), lockedAfter);
            writeLog(sku.getId(), orderNo, bizType, bizNo, RESERVE, 0, quantity,
                    value(sku.getStock()), lockedAfter, "订单库存预占");
        }
        return MallStockMutationResponse.ok();
    }

    @Transactional(rollbackFor = Exception.class)
    public MallStockMutationResponse confirm(MallStockMutationRequest request) {
        String orderNo = validateRequest(request);
        String bizType = validateBizType(request);
        String bizNo = validateBizNo(request);
        if (stockLogMapper.existsBizChange(bizType, bizNo, DEDUCT)) {
            return MallStockMutationResponse.ok();
        }
        Map<Long, Integer> items = normalizeItems(request);
        Map<Long, MallSku> skus = lockSkus(items);
        if (stockLogMapper.existsBizChange(bizType, bizNo, DEDUCT)) {
            return MallStockMutationResponse.ok();
        }
        if (!stockLogMapper.existsBizChange(bizType, bizNo, RESERVE)) {
            return MallStockMutationResponse.fail("订单尚未预占库存");
        }
        for (Map.Entry<Long, Integer> entry : items.entrySet()) {
            MallSku sku = skus.get(entry.getKey());
            int quantity = entry.getValue();
            if (sku == null || value(sku.getStock()) < quantity
                    || value(sku.getLockedStock()) < quantity) {
                return MallStockMutationResponse.fail("预占库存状态异常");
            }
        }
        for (Map.Entry<Long, Integer> entry : items.entrySet()) {
            MallSku sku = skus.get(entry.getKey());
            int quantity = entry.getValue();
            int stockAfter = value(sku.getStock()) - quantity;
            int lockedAfter = value(sku.getLockedStock()) - quantity;
            updateState(sku.getId(), stockAfter, lockedAfter);
            writeLog(sku.getId(), orderNo, bizType, bizNo, DEDUCT, -quantity, -quantity,
                    stockAfter, lockedAfter, "支付成功扣减库存");
        }
        return MallStockMutationResponse.ok();
    }

    @Transactional(rollbackFor = Exception.class)
    public MallStockMutationResponse release(MallStockMutationRequest request) {
        String orderNo = validateRequest(request);
        String bizType = validateBizType(request);
        String bizNo = validateBizNo(request);
        if (stockLogMapper.existsBizChange(bizType, bizNo, RELEASE)) {
            return MallStockMutationResponse.ok();
        }
        Map<Long, Integer> items = normalizeItems(request);
        Map<Long, MallSku> skus = lockSkus(items);
        if (stockLogMapper.existsBizChange(bizType, bizNo, RELEASE)) {
            return MallStockMutationResponse.ok();
        }
        if (stockLogMapper.existsBizChange(bizType, bizNo, DEDUCT)) {
            // 支付侧远程扣减成功但本地支付事务回滚时会留下 DEDUCT。
            // 调用方只在订单仍为未支付取消时进入这里；将扣减原额补回，消除悬挂库存。
            if (stockLogMapper.existsBizChange(bizType, bizNo, RESTORE)) {
                return MallStockMutationResponse.ok();
            }
            for (Map.Entry<Long, Integer> entry : items.entrySet()) {
                MallSku sku = skus.get(entry.getKey());
                int quantity = entry.getValue();
                if (sku == null) {
                    return MallStockMutationResponse.fail("SKU 不存在");
                }
                int stockAfter = value(sku.getStock()) + quantity;
                updateState(sku.getId(), stockAfter, value(sku.getLockedStock()));
                writeLog(sku.getId(), orderNo, bizType, bizNo, RESTORE, quantity, 0,
                        stockAfter, value(sku.getLockedStock()), "未支付订单补偿回补库存");
            }
            return MallStockMutationResponse.ok();
        }
        if (!stockLogMapper.existsBizChange(bizType, bizNo, RESERVE)) {
            return MallStockMutationResponse.ok();
        }
        for (Map.Entry<Long, Integer> entry : items.entrySet()) {
            MallSku sku = skus.get(entry.getKey());
            if (sku == null || value(sku.getLockedStock()) < entry.getValue()) {
                return MallStockMutationResponse.fail("预占库存状态异常");
            }
        }
        for (Map.Entry<Long, Integer> entry : items.entrySet()) {
            MallSku sku = skus.get(entry.getKey());
            int quantity = entry.getValue();
            int lockedAfter = value(sku.getLockedStock()) - quantity;
            updateState(sku.getId(), value(sku.getStock()), lockedAfter);
            writeLog(sku.getId(), orderNo, bizType, bizNo, RELEASE, 0, -quantity,
                    value(sku.getStock()), lockedAfter, "未支付取消释放库存");
        }
        return MallStockMutationResponse.ok();
    }

    @Transactional(rollbackFor = Exception.class)
    public MallStockMutationResponse restore(MallStockMutationRequest request) {
        String orderNo = validateRequest(request);
        String bizType = validateBizType(request);
        String bizNo = validateBizNo(request);
        if (stockLogMapper.existsBizChange(bizType, bizNo, RESTORE)) {
            return MallStockMutationResponse.ok();
        }
        Map<Long, Integer> items = normalizeItems(request);
        Map<Long, MallSku> skus = lockSkusIncludingDeleted(items);
        if (stockLogMapper.existsBizChange(bizType, bizNo, RESTORE)) {
            return MallStockMutationResponse.ok();
        }
        if (!stockLogMapper.existsBizChange(bizType, bizNo, DEDUCT)) {
            return MallStockMutationResponse.fail("订单尚未支付，不能回补库存");
        }
        for (Map.Entry<Long, Integer> entry : items.entrySet()) {
            if (skus.get(entry.getKey()) == null) {
                return MallStockMutationResponse.fail("SKU 不存在");
            }
        }
        for (Map.Entry<Long, Integer> entry : items.entrySet()) {
            MallSku sku = skus.get(entry.getKey());
            int quantity = entry.getValue();
            int stockAfter = value(sku.getStock()) + quantity;
            skuMapper.updateStockStateIncludingDeleted(sku.getId(), stockAfter, value(sku.getLockedStock()));
            writeLog(sku.getId(), orderNo, bizType, bizNo, RESTORE, quantity, 0,
                    stockAfter, value(sku.getLockedStock()), "退款回补库存");
        }
        return MallStockMutationResponse.ok();
    }

    private Map<Long, MallSku> lockSkusIncludingDeleted(Map<Long, Integer> items) {
        Map<Long, MallSku> result = new LinkedHashMap<>();
        for (Long skuId : items.keySet().stream().sorted().toList()) {
            result.put(skuId, skuMapper.selectByIdForUpdateIncludingDeleted(skuId));
        }
        return result;
    }

    private Map<Long, MallSku> lockSkus(Map<Long, Integer> items) {
        Map<Long, MallSku> result = new LinkedHashMap<>();
        for (Long skuId : items.keySet().stream().sorted().toList()) {
            result.put(skuId, skuMapper.selectByIdForUpdate(skuId));
        }
        return result;
    }

    private Map<Long, MallProduct> loadProducts(Iterable<MallSku> skus) {
        List<Long> productIds = new ArrayList<>();
        for (MallSku sku : skus) {
            if (sku != null && sku.getProductId() != null) {
                productIds.add(sku.getProductId());
            }
        }
        if (productIds.isEmpty()) {
            return Map.of();
        }
        List<MallProduct> products = productMapper.selectBatchIds(productIds.stream().distinct().toList());
        return products == null ? Map.of() : products.stream()
                .collect(Collectors.toMap(MallProduct::getId, Function.identity()));
    }

    private Map<Long, Integer> normalizeItems(MallStockMutationRequest request) {
        Map<Long, Integer> items = new LinkedHashMap<>();
        for (MallStockItemRequest item : request.getItems()) {
            if (item == null || item.getSkuId() == null || item.getQuantity() == null
                    || item.getQuantity() <= 0) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "库存明细不合法");
            }
            try {
                items.merge(item.getSkuId(), item.getQuantity(), Math::addExact);
            } catch (ArithmeticException e) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "库存明细数量溢出");
            }
        }
        if (items.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "库存明细不能为空");
        }
        return items;
    }

    private String validateBizType(MallStockMutationRequest request) {
        return StringUtils.hasText(request.getBizType()) ? request.getBizType().trim().toUpperCase() : "ORDER";
    }

    private String validateBizNo(MallStockMutationRequest request) {
        return StringUtils.hasText(request.getBizNo()) ? request.getBizNo().trim() : request.getOrderNo().trim();
    }

    private String validateRequest(MallStockMutationRequest request) {
        if (request == null || !StringUtils.hasText(request.getOrderNo())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单号不能为空");
        }
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "库存明细不能为空");
        }
        return request.getOrderNo().trim();
    }

    private void updateState(Long skuId, int stock, int lockedStock) {
        int affected = skuMapper.updateStockState(skuId, stock, lockedStock);
        if (affected != 1) {
            throw new BusinessException(ResultCode.ERROR, "库存更新失败");
        }
    }

    private void writeLog(Long skuId, String orderNo, String bizType, String bizNo, String changeType,
                          int stockDelta, int lockedDelta,
                          int stockAfter, int lockedAfter, String remark) {
        MallStockLog log = new MallStockLog();
        log.setSkuId(skuId);
        log.setOrderNo(orderNo);
        log.setBizNo(bizNo);
        log.setBizType(bizType);
        log.setChangeType(changeType);
        log.setStockDelta(stockDelta);
        log.setLockedDelta(lockedDelta);
        log.setStockAfter(stockAfter);
        log.setLockedAfter(lockedAfter);
        log.setOperatorType("SYSTEM");
        log.setOperatorId("TRADE");
        log.setRemark(remark);
        stockLogMapper.insert(log);
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }
}


