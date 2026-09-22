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

    private String nextNo(String prefix) {
        return prefix + LocalDateTime.now().format(FMT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }
}
