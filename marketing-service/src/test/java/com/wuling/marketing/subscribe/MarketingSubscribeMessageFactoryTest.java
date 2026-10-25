package com.wuling.marketing.subscribe;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketingSubscribeMessageFactoryTest {

    private final MarketingSubscribeMessageFactory factory = new MarketingSubscribeMessageFactory();

    @Test
    void buildsPointsChangeWithSystemStoreAndSignedAmount() {
        Map<String, Map<String, String>> data = factory.build(
                "pointsChange",
                "五一广场店",
                Map.of(
                        "amount", 12L,
                        "balanceAfter", 108L,
                        "reason", "每日签到",
                        "time", LocalDateTime.of(2026, 10, 22, 15, 30)
                ));

        assertEquals("五一广场店", data.get("thing6").get("value"));
        assertEquals("+12", data.get("character_string2").get("value"));
        assertEquals("每日签到", data.get("thing3").get("value"));
        assertEquals("2026-10-22 15:30", data.get("time7").get("value"));
    }

    @Test
    void buildsPointsExchangeWithRemainingBalanceAndDate() {
        Map<String, Map<String, String>> data = factory.build(
                "pointsExchange",
                null,
                Map.of(
                        "productName", "公益宠粮",
                        "quantity", 2,
                        "points", 40L,
                        "balanceAfter", 60L,
                        "time", LocalDateTime.of(2026, 10, 22, 16, 5)
                ));

        assertEquals("公益宠粮", data.get("thing1").get("value"));
        assertEquals("2", data.get("number2").get("value"));
        assertEquals("40", data.get("number3").get("value"));
        assertEquals("60", data.get("number4").get("value"));
        assertEquals("2026-10-22", data.get("date5").get("value"));
    }

    @Test
    void buildsStoredValueRechargeWithYuanAmount() {
        Map<String, Map<String, String>> data = factory.build(
                "storedValueRecharge",
                "梅溪湖店",
                Map.of(
                        "amountFen", 10000L,
                        "time", LocalDateTime.of(2026, 10, 22, 17, 0)
                ));

        assertEquals("梅溪湖店", data.get("thing5").get("value"));
        assertEquals("100.00", data.get("amount12").get("value"));
        assertEquals("2026-10-22 17:00", data.get("time13").get("value"));
    }

    @Test
    void buildsBalanceChangeWithSignedAmountAndCurrentBalance() {
        Map<String, Map<String, String>> data = factory.build(
                "balanceChange",
                "梅溪湖店",
                Map.of(
                        "amountFen", -1200L,
                        "balanceAfterFen", 8800L,
                        "reason", "余额支付",
                        "time", LocalDateTime.of(2026, 10, 22, 18, 8)
                ));

        assertEquals("-12.00", data.get("amount2").get("value"));
        assertEquals("88.00", data.get("amount1").get("value"));
        assertEquals("余额支付", data.get("thing3").get("value"));
        assertEquals("2026-10-22 18:08", data.get("time10").get("value"));
    }

    @Test
    void buildsCouponArrivalAndTruncatesThingFields() {
        String longName = "一二三四五六七八九十一二三四五六七八九十一";
        Map<String, Map<String, String>> data = factory.build(
                "couponArrival",
                null,
                Map.of(
                        "couponName", longName,
                        "quantity", 2,
                        "expireAt", LocalDateTime.of(2026, 10, 31, 23, 59),
                        "remark", "充值赠券"
                ));

        assertEquals("一二三四五六七八九十一二三四五六七八九…", data.get("thing1").get("value"));
        assertEquals("2", data.get("number9").get("value"));
        assertEquals("2026-10-31 23:59", data.get("time3").get("value"));
        assertEquals("充值赠券", data.get("thing4").get("value"));
    }

    @Test
    void buildsCouponExpiryWithAggregatedTip() {
        Map<String, Map<String, String>> data = factory.build(
                "couponExpiry",
                null,
                Map.of(
                        "couponName", "五零时光3元代金券",
                        "amountFen", 300L,
                        "expireAt", LocalDateTime.of(2026, 10, 25, 23, 59),
                        "tip", "另有2张券即将过期"
                ));

        assertEquals("五零时光3元代金券", data.get("thing10").get("value"));
        assertEquals("3.00", data.get("amount5").get("value"));
        assertEquals("另有2张券即将过期", data.get("thing6").get("value"));
    }
    @Test
    void acceptsIsoTimestampAfterQueueSerialization() {
        Map<String, Map<String, String>> data = factory.build(
                "pointsChange",
                "五一广场店",
                Map.of(
                        "amount", 12L,
                        "balanceAfter", 108L,
                        "reason", "每日签到",
                        "time", "2026-10-22T15:30:00"
                ));

        assertEquals("2026-10-22 15:30", data.get("time7").get("value"));
    }
}
