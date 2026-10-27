package com.wuling.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.common.api.PageResult;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.trade.dto.CreateAfterSaleRequest;
import com.wuling.trade.dto.MallAfterSaleVO;
import com.wuling.trade.entity.MallAfterSale;
import com.wuling.trade.entity.Order;
import com.wuling.trade.entity.OrderItem;
import com.wuling.trade.entity.Refund;
import com.wuling.trade.mapper.MallAfterSaleMapper;
import com.wuling.trade.mapper.OrderItemMapper;
import com.wuling.trade.mapper.OrderMapper;
import com.wuling.trade.mapper.RefundMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** 商城退货退款申请、查询与审核。 */
@Service
public class MallAfterSaleService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<MallAfterSaleVO.Item>> ITEM_LIST = new TypeReference<>() {};
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<String> ACTIVE = List.of("PENDING", "APPROVED", "REFUNDING");
    private static final List<String> ELIGIBLE_ORDER_STATUS = List.of("SHIPPED", "COMPLETED");

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final MallAfterSaleMapper afterSaleMapper;
    private final RefundService refundService;
    private final RefundMapper refundMapper;

    public MallAfterSaleService(OrderMapper orderMapper,
                                OrderItemMapper orderItemMapper,
                                MallAfterSaleMapper afterSaleMapper,
                                RefundService refundService) {
        this(orderMapper, orderItemMapper, afterSaleMapper, refundService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MallAfterSaleService(OrderMapper orderMapper,
                                OrderItemMapper orderItemMapper,
                                MallAfterSaleMapper afterSaleMapper,
                                RefundService refundService,
                                RefundMapper refundMapper) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.afterSaleMapper = afterSaleMapper;
        this.refundService = refundService;
        this.refundMapper = refundMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public MallAfterSaleVO create(Long userId, String orderNo, CreateAfterSaleRequest request) {
        Order order = lockOrder(orderNo);
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权申请该订单售后");
        }
        if (!"MALL".equalsIgnoreCase(order.getOrderType())
                || !ELIGIBLE_ORDER_STATUS.contains(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅已发货或已完成商城订单可申请退货退款");
        }
        if ("PENDING".equalsIgnoreCase(order.getRefundStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单退款处理中，请勿重复申请");
        }
        MallAfterSale active = afterSaleMapper.selectActiveByOrderIdForUpdate(order.getId());
        if (active != null && ACTIVE.contains(active.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该订单已有处理中的售后申请");
        }
        List<OrderItem> orderItems = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, order.getId()).orderByAsc(OrderItem::getId));
        Map<Long, OrderItem> byId = new LinkedHashMap<>();
        for (OrderItem item : orderItems) {
            byId.put(item.getId(), item);
        }
        Map<Long, Integer> requested = new LinkedHashMap<>();
        for (CreateAfterSaleRequest.Item line : request.getItems()) {
            requested.merge(line.getOrderItemId(), line.getQuantity(), Math::addExact);
        }
        List<MallAfterSaleVO.Item> snapshots = new ArrayList<>();
        long refundAmount = 0L;
        for (Map.Entry<Long, Integer> entry : requested.entrySet()) {
            OrderItem source = byId.get(entry.getKey());
            if (source == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "退货商品不属于该订单");
            }
            int quantity = entry.getValue();
            int total = source.getQuantity() == null ? 0 : source.getQuantity();
            int refunded = afterSaleMapper.selectSumRefundedQuantity(order.getId(), source.getId());
            if (quantity > Math.max(0, total - refunded)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "退货数量超过剩余可退数量");
            }
            long unitPrice = source.getUnitPrice() == null ? 0L : source.getUnitPrice();
            long lineAmount = Math.multiplyExact(unitPrice, quantity);
            refundAmount = Math.addExact(refundAmount, lineAmount);
            MallAfterSaleVO.Item snapshot = new MallAfterSaleVO.Item();
            snapshot.setOrderItemId(source.getId());
            snapshot.setSkuId(source.getMallSkuId());
            snapshot.setSkuCode(source.getProductId());
            snapshot.setName(source.getProductName());
            snapshot.setSpec(specText(source.getSpecSnapshot()));
            snapshot.setImage(source.getImage());
            snapshot.setQuantity(quantity);
            snapshot.setUnitPrice(unitPrice);
            snapshot.setDistributionCommission(source.getDistributionCommission());
            snapshot.setLineAmount(lineAmount);
            snapshots.add(snapshot);
        }
        LocalDateTime now = LocalDateTime.now();
        MallAfterSale afterSale = new MallAfterSale();
        afterSale.setAfterSaleNo(nextNo());
        afterSale.setOrderId(order.getId());
        afterSale.setOrderNo(order.getOrderNo());
        afterSale.setUserId(userId);
        afterSale.setStatus("PENDING");
        afterSale.setReason(request.getReason().trim());
        afterSale.setRefundAmount(refundAmount);
        afterSale.setItems(writeItems(snapshots));
        afterSale.setApplyTime(now);
        afterSaleMapper.insert(afterSale);
        return toVO(afterSale);
    }

    @Transactional(rollbackFor = Exception.class)
    public MallAfterSaleVO approve(String afterSaleNo, String reviewer, String remark) {
        MallAfterSale afterSale = requireByNoForUpdate(afterSaleNo);
        if (!"PENDING".equals(afterSale.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅待审核售后单可通过");
        }
        Order order = orderMapper.selectById(afterSale.getOrderId());
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "原订单不存在");
        }
        Refund refund = refundService.refundAfterSale(order, afterSale, remark);
        LocalDateTime now = LocalDateTime.now();
        // 储值余额支付会在 refundAfterSale 内同步推进到 SUCCESS/FAILED；
        // 回查数据库失败时保留已推进的内存状态，不能把已成功的退款降级为 REFUNDING。
        Refund persistedRefund = refund == null || refund.getId() == null || refundMapper == null
                ? null : refundMapper.selectById(refund.getId());
        Refund latestRefund = persistedRefund == null ? refund : persistedRefund;
        String finalStatus = finalAfterSaleStatus(latestRefund);
        MallAfterSale patch = new MallAfterSale();
        patch.setId(afterSale.getId());
        patch.setStatus(finalStatus);
        patch.setReviewTime(now);
        patch.setReviewer(reviewer);
        patch.setRefundId(latestRefund == null ? null : latestRefund.getId());
        if ("SUCCESS".equals(finalStatus)) patch.setCompleteTime(now);
        afterSaleMapper.updateById(patch);
        afterSale.setStatus(finalStatus);
        afterSale.setReviewTime(now);
        afterSale.setReviewer(reviewer);
        afterSale.setRefundId(latestRefund == null ? null : latestRefund.getId());
        if ("SUCCESS".equals(finalStatus)) afterSale.setCompleteTime(now);
        MallAfterSaleVO approved = toVO(afterSale);
        approved.setStatus(finalStatus);
        approved.setReviewer(reviewer);
        approved.setReviewTime(fmt(now));
        approved.setRefundId(latestRefund == null ? null : latestRefund.getId());
        approved.setCompleteTime(fmt(afterSale.getCompleteTime()));
        return approved;
    }

    @Transactional(rollbackFor = Exception.class)
    public MallAfterSaleVO reject(String afterSaleNo, String reviewer, String remark) {
        MallAfterSale afterSale = requireByNoForUpdate(afterSaleNo);
        if (!"PENDING".equals(afterSale.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅待审核售后单可拒绝");
        }
        if (!StringUtils.hasText(remark)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "驳回售后必须填写原因");
        }
        LocalDateTime now = LocalDateTime.now();
        MallAfterSale patch = new MallAfterSale();
        patch.setId(afterSale.getId());
        patch.setStatus("REJECTED");
        patch.setReviewTime(now);
        patch.setReviewer(reviewer);
        patch.setReason(remark.trim());
        afterSaleMapper.updateById(patch);
        afterSale.setStatus("REJECTED");
        afterSale.setReviewTime(now);
        afterSale.setReviewer(reviewer);
        afterSale.setReason(remark.trim());
        return toVO(afterSale);
    }

    public List<MallAfterSaleVO> listByOrder(Long userId, String orderNo) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo).last("limit 1"));
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权查看该订单售后");
        }
        return afterSaleMapper.selectList(new LambdaQueryWrapper<MallAfterSale>()
                        .eq(MallAfterSale::getOrderId, order.getId())
                        .orderByDesc(MallAfterSale::getId))
                .stream().map(this::toVO).toList();
    }

    public PageResult<MallAfterSaleVO> listByUser(Long userId, long current, long size) {
        long safePage = Math.max(1L, current);
        long safeSize = Math.min(100L, Math.max(1L, size));
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<MallAfterSale> page =
                afterSaleMapper.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(safePage, safeSize),
                        new LambdaQueryWrapper<MallAfterSale>()
                                .eq(MallAfterSale::getUserId, userId)
                                .orderByDesc(MallAfterSale::getId));
        return PageResult.of(page.getRecords().stream().map(this::toVO).toList(),
                page.getCurrent(), page.getSize(), page.getTotal());
    }

    public PageResult<MallAfterSaleVO> listForAdmin(long current, long size,
                                                    String status, String search) {
        long safePage = Math.max(1L, current);
        long safeSize = Math.min(100L, Math.max(1L, size));
        LambdaQueryWrapper<MallAfterSale> query = new LambdaQueryWrapper<MallAfterSale>()
                .eq(StringUtils.hasText(status), MallAfterSale::getStatus, status)
                .and(StringUtils.hasText(search), wrapper -> wrapper
                        .like(MallAfterSale::getAfterSaleNo, search)
                        .or().like(MallAfterSale::getOrderNo, search))
                .orderByDesc(MallAfterSale::getId);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<MallAfterSale> page =
                afterSaleMapper.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                        safePage, safeSize), query);
        return PageResult.of(page.getRecords().stream().map(this::toVO).toList(),
                page.getCurrent(), page.getSize(), page.getTotal());
    }

    public MallAfterSaleVO detail(Long userId, String afterSaleNo) {
        MallAfterSale afterSale = requireByNo(afterSaleNo);
        if (userId != null && !afterSale.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权查看该售后单");
        }
        return toVO(afterSale);
    }

    public MallAfterSale requireByNo(String afterSaleNo) {
        MallAfterSale afterSale = afterSaleMapper.selectByAfterSaleNo(afterSaleNo);
        if (afterSale == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "售后单不存在");
        }
        return afterSale;
    }

    private Order lockOrder(String orderNo) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo).last("limit 1 for update"));
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        return order;
    }

    private String finalAfterSaleStatus(Refund refund) {
        if (refund == null || refund.getStatus() == null) {
            return "REFUNDING";
        }
        if ("SUCCESS".equalsIgnoreCase(refund.getStatus())) {
            return "SUCCESS";
        }
        if ("FAILED".equalsIgnoreCase(refund.getStatus())) {
            return "FAILED";
        }
        return "REFUNDING";
    }

    private MallAfterSale requireByNoForUpdate(String afterSaleNo) {
        MallAfterSale afterSale = afterSaleMapper.selectByAfterSaleNoForUpdate(afterSaleNo);
        if (afterSale == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "售后单不存在");
        }
        return afterSale;
    }

    public MallAfterSaleVO toVO(MallAfterSale afterSale) {
        MallAfterSaleVO vo = new MallAfterSaleVO();
        vo.setId(afterSale.getId());
        vo.setAfterSaleNo(afterSale.getAfterSaleNo());
        vo.setOrderId(afterSale.getOrderId());
        vo.setOrderNo(afterSale.getOrderNo());
        vo.setUserId(afterSale.getUserId());
        vo.setStatus(afterSale.getStatus());
        vo.setReason(afterSale.getReason());
        vo.setRefundAmount(afterSale.getRefundAmount());
        vo.setItems(readItems(afterSale.getItems()));
        vo.setApplyTime(fmt(afterSale.getApplyTime()));
        vo.setReviewTime(fmt(afterSale.getReviewTime()));
        vo.setReviewer(afterSale.getReviewer());
        vo.setCompleteTime(fmt(afterSale.getCompleteTime()));
        vo.setRefundId(afterSale.getRefundId());
        if (afterSale.getRefundId() != null) {
            Refund refund = refundMapper == null ? null : refundMapper.selectById(afterSale.getRefundId());
            if (refund != null) {
                vo.setRefundNo(refund.getRefundNo());
                vo.setRefundStatus(refund.getStatus());
                if ("FAILED".equalsIgnoreCase(refund.getStatus())) {
                    vo.setRefundFailReason(refund.getReason());
                }
            }
        }
        return vo;
    }

    private List<MallAfterSaleVO.Item> readItems(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            return JSON.readValue(json, ITEM_LIST);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.ERROR, "售后商品快照损坏");
        }
    }

    private String writeItems(List<MallAfterSaleVO.Item> items) {
        try {
            return JSON.writeValueAsString(items);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.ERROR, "售后商品快照序列化失败");
        }
    }

    private String specText(String snapshot) {
        if (!StringUtils.hasText(snapshot)) return null;
        try {
            return JSON.readTree(snapshot).path("value").asText(snapshot);
        } catch (Exception e) {
            return snapshot;
        }
    }

    private String fmt(LocalDateTime time) {
        return time == null ? null : time.format(TIME_FMT);
    }

    private String nextNo() {
        return "AS" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
    }
}

