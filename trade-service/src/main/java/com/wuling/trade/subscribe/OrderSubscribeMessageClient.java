package com.wuling.trade.subscribe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.trade.dto.OrderDTO;
import com.wuling.trade.entity.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微信小程序“下单成功”订阅消息客户端。
 *
 * <p>只负责组装模板字段并调用微信接口。生产端只登记事件，消费端通过
 * {@link #sendEvent(Map, String, String)} 发送，避免支付线程直接访问微信。</p>
 */
@Component
public class OrderSubscribeMessageClient {

    private static final Logger log = LoggerFactory.getLogger(OrderSubscribeMessageClient.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy年M月d日");
    private static final int THING_MAX_CODE_POINTS = 20;
    private static final int CHARACTER_STRING_MAX_CODE_POINTS = 32;
    /** 模板「备注」字段固定文案：不再下发用户填写的订单备注。 */
    static final String REMARK_PREPARATION_TEXT = "您已下单成功，餐点准备中！";

    private final WxMiniappSubscribeProperties properties;
    private final MiniAppAccessTokenProvider tokenProvider;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public OrderSubscribeMessageClient(WxMiniappSubscribeProperties properties,
                                       MiniAppAccessTokenProvider tokenProvider) {
        this(properties, tokenProvider, buildRestTemplate(properties.getHttpTimeoutMs()));
    }

    OrderSubscribeMessageClient(WxMiniappSubscribeProperties properties,
                                MiniAppAccessTokenProvider tokenProvider,
                                RestTemplate restTemplate) {
        this.properties = properties;
        this.tokenProvider = tokenProvider;
        this.restTemplate = restTemplate;
    }

    /** 兼容旧调用：直接发送订单对象；新链路只应从消费者调用 sendEvent。 */
    public boolean send(Order order, OrderDTO dto, String openid) {
        SendResult result = sendEvent(buildEventValues(order, dto), buildPage(order), openid);
        return result.errcode() == 0;
    }

    /** 从不可变事件值发送；返回微信原始结果供消费者判定重试/终态。 */
    public SendResult sendEvent(Map<String, Object> values, String page, String openid) {
        if (!StringUtils.hasText(openid)) {
            return new SendResult(-1, "openid 为空");
        }
        if (!StringUtils.hasText(properties.getOrderSubscribe().getTemplateId())) {
            return new SendResult(-1, "模板 ID 未配置");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("touser", openid);
        payload.put("template_id", properties.getOrderSubscribe().getTemplateId());
        if (StringUtils.hasText(page)) {
            payload.put("page", page.trim().replaceFirst("^/+", ""));
        }
        String state = properties.getOrderSubscribe().getMiniprogramState();
        payload.put("miniprogram_state", StringUtils.hasText(state) ? state : "formal");
        payload.put("lang", "zh_CN");
        payload.put("data", buildData(values));

        SendResponse response = post(payload);
        if (response.errcode() == 40001) {
            tokenProvider.invalidate();
            response = post(payload);
        }
        return new SendResult(response.errcode(), response.errmsg());
    }

    Map<String, Object> buildPayload(Order order, OrderDTO dto, String openid) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("touser", openid);
        payload.put("template_id", properties.getOrderSubscribe().getTemplateId());
        String page = buildPage(order);
        if (StringUtils.hasText(page)) {
            payload.put("page", page);
        }
        String state = properties.getOrderSubscribe().getMiniprogramState();
        payload.put("miniprogram_state", StringUtils.hasText(state) ? state : "formal");
        payload.put("lang", "zh_CN");
        payload.put("data", buildData(order, dto));
        return payload;
    }

    Map<String, Map<String, String>> buildData(Order order, OrderDTO dto) {
        return buildData(buildEventValues(order, dto));
    }

    Map<String, Map<String, String>> buildData(Map<String, Object> values) {
        Map<String, Object> safe = values == null ? Map.of() : values;
        Map<String, Map<String, String>> data = new LinkedHashMap<>();
        data.put("character_string57", valueMap(characterString(safe.get("pickupCode"))));
        data.put("thing22", valueMap(thingValue(string(safe.get("store")), "门店")));
        data.put("thing5", valueMap(thingValue(string(safe.get("summary")), "商品")));
        data.put("date4", valueMap(string(safe.get("orderTime"), formatDate(null))));
        data.put("thing26", valueMap(thingValue(string(safe.get("remark")), REMARK_PREPARATION_TEXT)));
        return data;
    }

    /** 生成队列事件中的不可变业务值。 */
    public static Map<String, Object> buildEventValues(Order order, OrderDTO dto) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("pickupCode", characterStringValue(order, dto));
        values.put("store", storeValue(dto));
        values.put("summary", thingValue(dto == null ? null : dto.getSummary(), "商品"));
        values.put("orderTime", formatDate(order));
        values.put("remark", REMARK_PREPARATION_TEXT);
        return values;
    }

    /** 根据配置页和目标订单生成跳转页。 */
    public static String buildEventPage(String configuredPage, Order order) {
        if (!StringUtils.hasText(configuredPage)) {
            return "";
        }
        String normalized = configuredPage.trim().replaceFirst("^/+", "");
        String no = orderNo(order);
        if (!StringUtils.hasText(no)) {
            return normalized;
        }
        return normalized + "?orderNo=" + URLEncoder.encode(no, StandardCharsets.UTF_8);
    }

    private String buildPage(Order order) {
        return buildEventPage(properties.getOrderSubscribe().getPage(), order);
    }

    private static String characterStringValue(Order order, OrderDTO dto) {
        String pickupCode = dto == null ? null : dto.getPickupCode();
        if (!StringUtils.hasText(pickupCode) && order != null) {
            pickupCode = order.getPickupCode();
        }
        return characterString(pickupCode);
    }

    private static String characterString(Object raw) {
        String value = StringUtils.hasText(string(raw)) ? string(raw).trim() : "-";
        return truncateByCodePoints(value, CHARACTER_STRING_MAX_CODE_POINTS);
    }

    private static String formatDate(Order order) {
        LocalDateTime time = order == null ? null : order.getCreateTime();
        if (time == null && order != null) {
            time = order.getPayTime();
        }
        if (time == null) {
            time = LocalDateTime.now();
        }
        return time.format(DATE_FORMAT);
    }

    private static String storeValue(OrderDTO dto) {
        String store = dto == null ? null : dto.getStore();
        if (!StringUtils.hasText(store) || "-".equals(store.trim())) {
            return "门店";
        }
        return thingValue(store, "门店");
    }

    private static String thingValue(String raw, String fallback) {
        String value = StringUtils.hasText(raw) ? raw.trim() : fallback;
        value = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (!StringUtils.hasText(value)) {
            value = fallback;
        }
        return truncateByCodePoints(value, THING_MAX_CODE_POINTS);
    }

    private static String string(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private static String string(Object raw, String fallback) {
        return StringUtils.hasText(string(raw)) ? String.valueOf(raw) : fallback;
    }

    static String truncateByCodePoints(String value, int maxCodePoints) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) {
            return value;
        }
        int end = value.offsetByCodePoints(0, Math.max(1, maxCodePoints - 1));
        return value.substring(0, end) + "…";
    }

    private static Map<String, String> valueMap(String value) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("value", value);
        return item;
    }

    private SendResponse post(Map<String, Object> payload) {
        try {
            String accessToken = tokenProvider.getAccessToken();
            if (!StringUtils.hasText(accessToken)) {
                return new SendResponse(-1, "empty access_token");
            }
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String body = objectMapper.writeValueAsString(payload);
            String url = properties.getSubscribeSendUrl() + "?access_token=" + accessToken;
            ResponseEntity<String> response = restTemplate.postForEntity(
                    url, new HttpEntity<>(body, headers), String.class);
            JsonNode node = objectMapper.readTree(response.getBody());
            return new SendResponse(node.path("errcode").asInt(0), node.path("errmsg").asText(""));
        } catch (Exception e) {
            return new SendResponse(-1, sanitize(e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private static String sanitize(String message) {
        if (!StringUtils.hasText(message)) {
            return "";
        }
        return message.replaceAll("access_token=[^&\\s]+", "access_token=***")
                .replaceAll("secret=[^&\\s]+", "secret=***");
    }

    private static String orderNo(Order order) {
        return order == null ? "-" : order.getOrderNo();
    }

    private static RestTemplate buildRestTemplate(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeout = timeoutMs > 0 ? timeoutMs : 3000;
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return new RestTemplate(factory);
    }

    public record SendResult(int errcode, String errmsg) {
    }

    private record SendResponse(int errcode, String errmsg) {
    }
}
