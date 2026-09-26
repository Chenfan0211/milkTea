package com.wuling.marketing.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wuling.common.api.PageResult;
import com.wuling.marketing.entity.GiftCard;
import com.wuling.marketing.entity.GiftCardDenomination;
import com.wuling.marketing.entity.GiftCardOrder;
import com.wuling.marketing.mapper.GiftCardDenominationMapper;
import com.wuling.marketing.mapper.GiftCardMapper;
import com.wuling.marketing.mapper.GiftCardOrderMapper;
import com.wuling.marketing.mapper.GiftCardRefundMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GiftCardServiceMetadataTest {

    private final GiftCardDenominationMapper denominationMapper = mock(GiftCardDenominationMapper.class);
    private final GiftCardMapper giftCardMapper = mock(GiftCardMapper.class);
    private final GiftCardOrderMapper orderMapper = mock(GiftCardOrderMapper.class);
    private final GiftCardRefundMapper refundMapper = mock(GiftCardRefundMapper.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final GiftCardService service =
            new GiftCardService(denominationMapper, giftCardMapper, orderMapper,
                    refundMapper, jdbcTemplate, 15L);

    private Map<String, Object> deletedDenominationRow() {
        return Map.of(
                "denomination_id", 77L,
                "card_name", "已下架抹茶卡",
                "card_image", "/assets/images/3x/gift-card-matcha.jpg",
                "group_title", "人气礼品卡",
                "amount", 10000L,
                "sale_price", 9000L,
                "deleted", 1);
    }

    @Test
    @DisplayName("我的礼品卡回显历史卡面元数据，逻辑删除面额仍可查询")
    void myCardsReturnsHistoricalMetadata() {
        GiftCard card = new GiftCard();
        card.setId(1L);
        card.setDenominationId(77L);
        when(giftCardMapper.selectList(any(Wrapper.class))).thenReturn(List.of(card));
        when(jdbcTemplate.queryForList(contains("select id as denomination_id"), any(Object[].class)))
                .thenReturn(List.of(deletedDenominationRow()));

        List<GiftCard> cards = service.myCards(9L);

        assertEquals(1, cards.size());
        assertEquals("已下架抹茶卡", cards.get(0).getCardName());
        assertEquals("人气礼品卡", cards.get(0).getGroupTitle());
        assertEquals(10000L, cards.get(0).getAmount());
        assertEquals(9000L, cards.get(0).getSalePrice());
        assertNotNull(cards.get(0).getCardImage());
    }

    @Test
    @DisplayName("礼品卡订单分页保持 records，并回显历史卡面元数据")
    void myOrdersKeepsPageResultAndReturnsHistoricalMetadata() {
        GiftCardOrder order = new GiftCardOrder();
        order.setId(2L);
        order.setDenominationId(77L);
        order.setAmount(10000L);
        when(orderMapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
            Page<GiftCardOrder> page = invocation.getArgument(0);
            page.setRecords(List.of(order));
            page.setTotal(1);
            return page;
        });
        when(jdbcTemplate.queryForList(contains("select id as denomination_id"), any(Object[].class)))
                .thenReturn(List.of(deletedDenominationRow()));

        PageResult<GiftCardOrder> result = service.myOrders(9L, 1, 10);

        assertEquals(1, result.getRecords().size());
        assertEquals("已下架抹茶卡", result.getRecords().get(0).getCardName());
        assertEquals("人气礼品卡", result.getRecords().get(0).getGroupTitle());
        assertEquals(9000L, result.getRecords().get(0).getSalePrice());
    }
}
