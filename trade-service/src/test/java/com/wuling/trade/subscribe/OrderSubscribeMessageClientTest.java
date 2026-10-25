package com.wuling.trade.subscribe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.trade.dto.OrderDTO;
import com.wuling.trade.entity.Order;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderSubscribeMessageClientTest {

    private static final String TEMPLATE_ID = "W-_sFdE4DpQbfs5aeXASyEvVIVkFBYz65Qjl8lQQJWM";

    private WxMiniappSubscribeProperties properties() {
        WxMiniappSubscribeProperties properties = new WxMiniappSubscribeProperties();
        properties.setAppId("wx-test");
        properties.setAppSecret("secret");
        properties.getOrderSubscribe().setEnabled(true);
        properties.getOrderSubscribe().setTemplateId(TEMPLATE_ID);
        properties.getOrderSubscribe().setPage("pages/order-detail/order-detail");
        properties.getOrderSubscribe().setMiniprogramState("formal");
        return properties;
    }

    private Order order() {
        Order order = new Order();
        order.setId(1L);
        order.setOrderNo("WX202610220001");
        order.setUserId(7L);
        order.setPickupCode("A-102");
        order.setRemark("少冰");
        order.setStatus("PAID");
        order.setCreateTime(LocalDateTime.of(2026, 10, 22, 12, 30));
        return order;
    }

    private OrderDTO dto() {
        OrderDTO dto = new OrderDTO();
        dto.setOrderNo("WX202610220001");
        dto.setPickupCode("A-102");
        dto.setStore("五零时光旗舰店");
        dto.setSummary("生椰拿铁 x1,美式 x2");
        dto.setPaidAmount(2500L);
        return dto;
    }

    @Test
    void sendsExactTemplateFields() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        MiniAppAccessTokenProvider tokenProvider = mock(MiniAppAccessTokenProvider.class);
        when(tokenProvider.getAccessToken()).thenReturn("token");
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"errcode\":0,\"errmsg\":\"ok\"}"));

        OrderSubscribeMessageClient client =
                new OrderSubscribeMessageClient(properties(), tokenProvider, restTemplate);
        assertTrue(client.send(order(), dto(), "openid-7"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<String>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(
                contains("/cgi-bin/message/subscribe/send?access_token=token"),
                captor.capture(),
                eq(String.class));
        JsonNode payload = new ObjectMapper().readTree(captor.getValue().getBody());
        assertEquals(TEMPLATE_ID, payload.path("template_id").asText());
        assertEquals("openid-7", payload.path("touser").asText());
        assertEquals("pages/order-detail/order-detail?orderNo=WX202610220001", payload.path("page").asText());
        assertEquals("formal", payload.path("miniprogram_state").asText());
        JsonNode data = payload.path("data");
        assertEquals("A-102", data.path("character_string57").path("value").asText());
        assertEquals("五零时光旗舰店", data.path("thing22").path("value").asText());
        assertEquals("生椰拿铁 x1,美式 x2", data.path("thing5").path("value").asText());
        assertEquals("2026年10月22日", data.path("date4").path("value").asText());
        assertEquals("您已下单成功，餐点准备中！", data.path("thing26").path("value").asText());
    }

    @Test
    void truncatesAndFallsBackForThingFields() {
        OrderSubscribeMessageClient client = new OrderSubscribeMessageClient(
                properties(), mock(MiniAppAccessTokenProvider.class), mock(RestTemplate.class));
        Order order = order();
        order.setRemark("   ");
        order.setPickupCode("");
        OrderDTO dto = dto();
        dto.setStore("这是一个超过二十个字符长度的超长门店名称用于验证截断行为");
        dto.setSummary("");
        dto.setPickupCode("");

        Map<String, Map<String, String>> data = client.buildData(order, dto);
        assertEquals("-", data.get("character_string57").get("value"));
        assertEquals("商品", data.get("thing5").get("value"));
        assertEquals("您已下单成功，餐点准备中！", data.get("thing26").get("value"));
        String store = data.get("thing22").get("value");
        assertEquals(20, store.codePointCount(0, store.length()));
        assertTrue(store.endsWith("…"));
    }

    @Test
    void remarkIsAlwaysPreparationText() {
        OrderSubscribeMessageClient client = new OrderSubscribeMessageClient(
                properties(), mock(MiniAppAccessTokenProvider.class), mock(RestTemplate.class));
        Order order = order();
        order.setRemark("少冰、不要糖");

        Map<String, Map<String, String>> data = client.buildData(order, dto());
        assertEquals("您已下单成功，餐点准备中！", data.get("thing26").get("value"));
    }

    @Test
    void storeDashFallsBackToDefault() {
        OrderSubscribeMessageClient client = new OrderSubscribeMessageClient(
                properties(), mock(MiniAppAccessTokenProvider.class), mock(RestTemplate.class));
        OrderDTO dto = dto();
        dto.setStore("-");

        Map<String, Map<String, String>> data = client.buildData(order(), dto);
        assertEquals("门店", data.get("thing22").get("value"));
    }

    @Test
    void usesPayTimeThenNowWhenCreateTimeMissing() {
        OrderSubscribeMessageClient client = new OrderSubscribeMessageClient(
                properties(), mock(MiniAppAccessTokenProvider.class), mock(RestTemplate.class));
        Order order = order();
        order.setCreateTime(null);
        order.setPayTime(LocalDateTime.of(2026, 10, 23, 9, 15));
        OrderDTO dto = dto();

        Map<String, Map<String, String>> data = client.buildData(order, dto);
        assertEquals("2026年10月23日", data.get("date4").get("value"));

        order.setPayTime(null);
        Map<String, Map<String, String>> fallback = client.buildData(order, dto);
        assertTrue(fallback.get("date4").get("value").matches("\\d{4}年\\d{1,2}月\\d{1,2}日"));
    }

    @Test
    void retriesOnceAfterTokenExpired() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        MiniAppAccessTokenProvider tokenProvider = mock(MiniAppAccessTokenProvider.class);
        when(tokenProvider.getAccessToken()).thenReturn("old-token", "new-token");
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"errcode\":40001,\"errmsg\":\"invalid credential\"}"))
                .thenReturn(ResponseEntity.ok("{\"errcode\":0,\"errmsg\":\"ok\"}"));

        OrderSubscribeMessageClient client =
                new OrderSubscribeMessageClient(properties(), tokenProvider, restTemplate);
        assertTrue(client.send(order(), dto(), "openid-7"));
        verify(tokenProvider).invalidate();
        verify(restTemplate, times(2)).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void doesNotRetryWhenUserHasNoSubscription() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        MiniAppAccessTokenProvider tokenProvider = mock(MiniAppAccessTokenProvider.class);
        when(tokenProvider.getAccessToken()).thenReturn("token");
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"errcode\":43101,\"errmsg\":\"user refuse to accept the msg\"}"));

        OrderSubscribeMessageClient client =
                new OrderSubscribeMessageClient(properties(), tokenProvider, restTemplate);
        assertFalse(client.send(order(), dto(), "openid-7"));
        verify(tokenProvider, never()).invalidate();
        verify(restTemplate, times(1)).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void neverThrowsWhenWechatIsUnreachable() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        MiniAppAccessTokenProvider tokenProvider = mock(MiniAppAccessTokenProvider.class);
        when(tokenProvider.getAccessToken()).thenReturn("token");
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new RuntimeException("connect timed out"));

        OrderSubscribeMessageClient client =
                new OrderSubscribeMessageClient(properties(), tokenProvider, restTemplate);
        assertFalse(client.send(order(), dto(), "openid-7"));
    }

    @Test
    void skipsWhenOpenidIsBlank() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        OrderSubscribeMessageClient client = new OrderSubscribeMessageClient(
                properties(), mock(MiniAppAccessTokenProvider.class), restTemplate);
        assertFalse(client.send(order(), dto(), " "));
        verifyNoInteractions(restTemplate);
    }
}