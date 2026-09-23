package com.wuling.marketing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.marketing.entity.GiftCard;
import com.wuling.marketing.entity.GiftCardDenomination;
import com.wuling.marketing.entity.GiftCardOrder;
import com.wuling.marketing.mapper.GiftCardDenominationMapper;
import com.wuling.marketing.mapper.GiftCardMapper;
import com.wuling.marketing.mapper.GiftCardOrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** 礼品卡：面额列表 / 购买（生成卡号）/ 我的卡 */
@Service
public class GiftCardService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final GiftCardDenominationMapper denominationMapper;
    private final GiftCardMapper giftCardMapper;
    private final GiftCardOrderMapper orderMapper;

    public GiftCardService(GiftCardDenominationMapper denominationMapper,
                           GiftCardMapper giftCardMapper,
                           GiftCardOrderMapper orderMapper) {
        this.denominationMapper = denominationMapper;
        this.giftCardMapper = giftCardMapper;
        this.orderMapper = orderMapper;
    }

    public List<GiftCardDenomination> listDenominations() {
        return denominationMapper.selectList(new LambdaQueryWrapper<GiftCardDenomination>()
                .eq(GiftCardDenomination::getStatus, "enabled")
                .orderByAsc(GiftCardDenomination::getAmount));
    }

    @Transactional(rollbackFor = Exception.class)
    public GiftCardOrder purchase(Long userId, Long denominationId) {
        GiftCardDenomination denom = denominationMapper.selectById(denominationId);
        if (denom == null || !"enabled".equals(denom.getStatus())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "礼品卡面额不存在");
        }
        GiftCardOrder order = new GiftCardOrder();
        order.setOrderNo(nextNo("GC"));
        order.setUserId(userId);
        order.setDenominationId(denominationId);
        order.setAmount(denom.getAmount());
        order.setPayStatus("PAID");
        order.setStatus("PAID");
        order.setPayTime(LocalDateTime.now());
        order.setVerifyStatus("UNVERIFIED");
        orderMapper.insert(order);

        GiftCard card = new GiftCard();
        card.setCardNo(nextNo("CARD"));
        card.setDenominationId(denominationId);
        card.setStatus("ACTIVE");
        card.setOwnerUserId(userId);
        giftCardMapper.insert(card);
        return order;
    }

    public List<GiftCard> myCards(Long userId) {
        return giftCardMapper.selectList(new LambdaQueryWrapper<GiftCard>()
                .eq(GiftCard::getOwnerUserId, userId)
                .orderByDesc(GiftCard::getId));
    }

    public List<GiftCardOrder> myOrders(Long userId) {
        return orderMapper.selectList(new LambdaQueryWrapper<GiftCardOrder>()
                .eq(GiftCardOrder::getUserId, userId)
                .orderByDesc(GiftCardOrder::getId));
    }

    /** 取消礼品卡订单：待支付/已支付均可取消；已支付取消需标记退款金额 */
    @Transactional(rollbackFor = Exception.class)
    public GiftCardOrder cancelOrder(Long userId, Long orderId) {
        GiftCardOrder order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "礼品卡订单不存在");
        }
        if ("CANCELED".equals(order.getStatus()) || "VERIFIED".equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "当前订单状态不可取消");
        }
        boolean paid = "PAID".equals(order.getPayStatus());
        order.setStatus("CANCELED");
        order.setCancelType(paid ? "paid" : "pending");
        if (paid) {
            order.setRefundAmount(order.getAmount());
        }
        orderMapper.updateById(order);
        return order;
    }

    /** 核销礼品卡订单：门店扫码核销，订单置 VERIFIED，对应卡置 USED */
    @Transactional(rollbackFor = Exception.class)
    public GiftCardOrder verifyOrder(Long userId, String orderNo) {
        GiftCardOrder order = orderMapper.selectOne(new LambdaQueryWrapper<GiftCardOrder>()
                .eq(GiftCardOrder::getOrderNo, orderNo)
                .eq(GiftCardOrder::getUserId, userId));
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "礼品卡订单不存在");
        }
        if (!"PAID".equals(order.getPayStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单未支付，无法核销");
        }
        if ("VERIFIED".equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单已核销");
        }
        order.setStatus("VERIFIED");
        order.setVerifyStatus("VERIFIED");
        order.setVerifyTime(LocalDateTime.now());
        orderMapper.updateById(order);

        // 该订单对应的卡置 USED（一单一卡）
        GiftCard card = giftCardMapper.selectOne(new LambdaQueryWrapper<GiftCard>()
                .eq(GiftCard::getOwnerUserId, userId)
                .eq(GiftCard::getDenominationId, order.getDenominationId())
                .eq(GiftCard::getStatus, "ACTIVE")
                .last("limit 1"));
        if (card != null) {
            card.setStatus("USED");
            giftCardMapper.updateById(card);
        }
        return order;
    }

    private String nextNo(String prefix) {
        return prefix + LocalDateTime.now().format(FMT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }
}
