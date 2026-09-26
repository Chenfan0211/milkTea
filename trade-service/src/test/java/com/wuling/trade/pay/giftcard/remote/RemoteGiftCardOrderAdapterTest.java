package com.wuling.trade.pay.giftcard.remote;

import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.trade.pay.giftcard.GiftCardOrderPort;
import com.wuling.trade.pay.giftcard.GiftCardRefundResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RemoteGiftCardOrderAdapterTest {

    private static final String ORDER_NO = "GC202609260001";
    private static final String INTERNAL_TOKEN = "internal-test-token";

    private MockRestServiceServer server;
    private RemoteGiftCardOrderAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://marketing.test");
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new RemoteGiftCardOrderAdapter(builder.build(), INTERNAL_TOKEN);
    }

    @Test
    void findsOrderFromMarketingInternalEndpoint() {
        server.expect(requestTo("http://marketing.test/internal/gift-card-orders/" + ORDER_NO))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Token", INTERNAL_TOKEN))
                .andRespond(withSuccess("""
                        {"found":true,
                         "orderNo":"GC202609260001",
                         "userId":7,
                         "amount":8800,
                         "payStatus":"UNPAID",
                         "status":"CREATED",
                         "refundStatus":"REFUNDING",
                         "verifyStatus":"UNVERIFIED",
                         "transactionId":null,
                         "expireTime":"2026-09-26T20:15:30"}
                        """, MediaType.APPLICATION_JSON));

        GiftCardOrderPort.GiftCardOrderView order = adapter.findByOrderNo(ORDER_NO);

        assertNotNull(order);
        assertEquals(ORDER_NO, order.getOrderNo());
        assertEquals(7L, order.getUserId());
        assertEquals(8800L, order.getAmount());
        assertEquals("UNPAID", order.getPayStatus());
        assertEquals("CREATED", order.getStatus());
        assertEquals("REFUNDING", order.getRefundStatus());
        assertEquals("UNVERIFIED", order.getVerifyStatus());
        assertEquals(LocalDateTime.of(2026, 9, 26, 20, 15, 30), order.getExpireTime());
        server.verify();
    }

    @Test
    void settlesThroughMarketingInternalEndpoint() {
        server.expect(requestTo("http://marketing.test/internal/gift-card-orders/" + ORDER_NO + "/settle"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Token", INTERNAL_TOKEN))
                .andExpect(jsonPath("$.transactionId").value("WX-1"))
                .andExpect(jsonPath("$.payerOpenid").value("openid-7"))
                .andExpect(jsonPath("$.callbackAmount").value(8800))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        adapter.settle(ORDER_NO, "WX-1", "openid-7", 8800L);

        server.verify();
    }

    @Test
    void refundLifecycleUsesMarketingInternalEndpoints() {
        server.expect(requestTo("http://marketing.test/internal/gift-card-orders/" + ORDER_NO + "/refund-begin"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Token", INTERNAL_TOKEN))
                .andExpect(jsonPath("$.reason").value("不需要了"))
                .andRespond(withSuccess("""
                        {"refundNo":"GR202609260001","orderNo":"GC202609260001",
                         "amount":8800,"status":"REFUNDING"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://marketing.test/internal/gift-card-orders/" + ORDER_NO + "/refund-confirm"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Token", INTERNAL_TOKEN))
                .andExpect(jsonPath("$.refundNo").value("GR202609260001"))
                .andExpect(jsonPath("$.wxRefundId").value("WXR1"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://marketing.test/internal/gift-card-orders/" + ORDER_NO + "/refund-fail"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Token", INTERNAL_TOKEN))
                .andExpect(jsonPath("$.refundNo").value("GR202609260001"))
                .andExpect(jsonPath("$.failReason").value("ABNORMAL"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        GiftCardRefundResult result = adapter.refundBegin(ORDER_NO, "不需要了");
        adapter.refundConfirm(ORDER_NO, "GR202609260001", "WXR1");
        adapter.refundFail(ORDER_NO, "GR202609260001", "ABNORMAL");

        assertEquals("GR202609260001", result.refundNo());
        assertEquals(8800L, result.amount());
        assertEquals("REFUNDING", result.status());
        server.verify();
    }

    @Test
    void settleRejectsMissingTransactionIdBeforeRemoteCall() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> adapter.settle(ORDER_NO, " ", "openid-7", 8800L));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        server.verify();
    }

    @Test
    void settleRejectsMissingAmountBeforeRemoteCall() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> adapter.settle(ORDER_NO, "WX-1", "openid-7", null));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        server.verify();
    }

    @Test
    void settleRejectsNonPositiveAmountBeforeRemoteCall() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> adapter.settle(ORDER_NO, "WX-1", "openid-7", 0L));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        server.verify();
    }
}
