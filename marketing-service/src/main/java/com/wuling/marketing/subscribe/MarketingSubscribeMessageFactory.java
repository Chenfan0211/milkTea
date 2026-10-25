package com.wuling.marketing.subscribe;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** 微信订阅模板字段构造器：统一金额、时间和 thing 字段约束。 */
@Component
public class MarketingSubscribeMessageFactory {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int THING_MAX_CODE_POINTS = 20;

    public Map<String, Map<String, String>> build(String templateKey, String storeName, Map<String, Object> values) {
        Map<String, Object> safe = values == null ? Map.of() : values;
        return switch (templateKey) {
            case "pointsChange" -> pointsChange(storeName, safe);
            case "pointsExchange" -> pointsExchange(safe);
            case "storedValueRecharge" -> storedValueRecharge(storeName, safe);
            case "balanceChange" -> balanceChange(storeName, safe);
            case "couponArrival" -> couponArrival(safe);
            case "couponExpiry" -> couponExpiry(safe);
            default -> throw new IllegalArgumentException("未知订阅模板: " + templateKey);
        };
    }

    private Map<String, Map<String, String>> pointsChange(String storeName, Map<String, Object> values) {
        Map<String, Map<String, String>> data = new LinkedHashMap<>();
        data.put("thing6", value(thing(storeName)));
        data.put("character_string2", value(signedLong(values.get("amount"))));
        data.put("thing3", value(thing(string(values.get("reason"), "时光币变动"))));
        data.put("time7", value(dateTime(values.get("time"))));
        return data;
    }

    private Map<String, Map<String, String>> pointsExchange(Map<String, Object> values) {
        Map<String, Map<String, String>> data = new LinkedHashMap<>();
        data.put("thing1", value(thing(string(values.get("productName"), "兑换商品"))));
        data.put("number2", value(String.valueOf(number(values.get("quantity"), 1L))));
        data.put("number3", value(String.valueOf(number(values.get("points"), 0L))));
        data.put("number4", value(String.valueOf(number(values.get("balanceAfter"), 0L))));
        data.put("date5", value(date(values.get("time"))));
        return data;
    }

    private Map<String, Map<String, String>> storedValueRecharge(String storeName, Map<String, Object> values) {
        Map<String, Map<String, String>> data = new LinkedHashMap<>();
        data.put("thing5", value(thing(storeName)));
        data.put("amount12", value(yuan(values.get("amountFen"))));
        data.put("time13", value(dateTime(values.get("time"))));
        return data;
    }

    private Map<String, Map<String, String>> balanceChange(String storeName, Map<String, Object> values) {
        Map<String, Map<String, String>> data = new LinkedHashMap<>();
        data.put("thing5", value(thing(storeName)));
        data.put("amount2", value(signedYuan(values.get("amountFen"))));
        data.put("amount1", value(yuan(values.get("balanceAfterFen"))));
        data.put("thing3", value(thing(string(values.get("reason"), "余额变动"))));
        data.put("time10", value(dateTime(values.get("time"))));
        return data;
    }

    private Map<String, Map<String, String>> couponArrival(Map<String, Object> values) {
        Map<String, Map<String, String>> data = new LinkedHashMap<>();
        data.put("thing1", value(thing(string(values.get("couponName"), "优惠券"))));
        data.put("number9", value(String.valueOf(number(values.get("quantity"), 1L))));
        data.put("time3", value(dateTime(values.get("expireAt"))));
        data.put("thing4", value(thing(string(values.get("remark"), "已放入券包"))));
        return data;
    }

    private Map<String, Map<String, String>> couponExpiry(Map<String, Object> values) {
        Map<String, Map<String, String>> data = new LinkedHashMap<>();
        data.put("thing10", value(thing(string(values.get("couponName"), "优惠券"))));
        data.put("amount5", value(yuan(values.get("amountFen"))));
        data.put("time4", value(dateTime(values.get("expireAt"))));
        data.put("thing6", value(thing(string(values.get("tip"), "请及时使用"))));
        return data;
    }

    static String thing(String raw) {
        String value = StringUtils.hasText(raw) ? raw.trim().replaceAll("[\\r\\n\\t]+", " ") : "";
        if (!StringUtils.hasText(value)) {
            value = "-";
        }
        int count = value.codePointCount(0, value.length());
        if (count <= THING_MAX_CODE_POINTS) {
            return value;
        }
        int end = value.offsetByCodePoints(0, Math.max(1, THING_MAX_CODE_POINTS - 1));
        return value.substring(0, end) + "…";
    }

    private static String signedLong(Object raw) {
        long value = number(raw, 0L);
        return value > 0 ? "+" + value : String.valueOf(value);
    }

    private static String yuan(Object raw) {
        long fen = number(raw, 0L);
        return BigDecimal.valueOf(fen).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String signedYuan(Object raw) {
        long fen = number(raw, 0L);
        String value = BigDecimal.valueOf(Math.abs(fen)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                .toPlainString();
        return fen < 0 ? "-" + value : "+" + value;
    }

    private static long number(Object raw, long fallback) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw == null) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String string(Object raw, String fallback) {
        return StringUtils.hasText(raw == null ? null : String.valueOf(raw)) ? String.valueOf(raw) : fallback;
    }

    private static String dateTime(Object raw) {
        return localDateTime(raw).format(DATE_TIME);
    }

    private static String date(Object raw) {
        return localDateTime(raw).format(DATE);
    }

    private static LocalDateTime localDateTime(Object raw) {
        if (raw instanceof LocalDateTime value) {
            return value;
        }
        if (raw != null) {
            String text = String.valueOf(raw).trim();
            try {
                return LocalDateTime.parse(text);
            } catch (Exception ignored) {
                // 兼容队列中可能出现的日期时间数组/历史格式，回退当前时间。
            }
        }
        return LocalDateTime.now();
    }

    private static Map<String, String> value(String value) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("value", value);
        return item;
    }
}
