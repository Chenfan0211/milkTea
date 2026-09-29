package com.wuling.trade.pay.payout;

import com.wuling.trade.port.UserQueryPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 出款通道选型与 mock 语义测试。 */
class PayoutGatewayResolverTest {

    @Test
    void resolverSelectsMockByDefault() {
        PayoutGatewayResolver resolver = new PayoutGatewayResolver(
                List.of(new MockPayoutGateway()), "mock");

        assertEquals("MOCK", resolver.activeChannel());
        assertTrue(resolver.isMockChannel());
        assertTrue(resolver.active().isImmediate(), "mock 通道应即时到账");
    }

    @Test
    void resolverFailsFastOnUnknownChannel() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                new PayoutGatewayResolver(List.of(new MockPayoutGateway()), "wxpay"));
    }

    @Test
    void mockTransferAcceptsImmediately() {
        PayoutGateway gateway = new MockPayoutGateway();
        PayoutGateway.PayoutResult result = gateway.transfer("WD1", 100L, "openid1", "测试");

        assertTrue(result.accepted());
        assertNotNull(result.batchNo());
    }

    @Test
    void payoutServiceQueriesOpenidBeforeTransfer() {
        UserQueryPort userQueryPort = mock(UserQueryPort.class);
        PayoutGatewayResolver resolver = new PayoutGatewayResolver(List.of(new MockPayoutGateway()), "mock");
        PayoutService service = new PayoutService(resolver, userQueryPort);

        // openid 缺失 → fail-closed
        when(userQueryPort.findOpenid(any())).thenReturn(null);
        PayoutGateway.PayoutResult noOpenid = service.apply("WD1", 100L, 1L, "测试");
        assertFalse(noOpenid.accepted(), "openid 缺失必须拒绝，不能静默成功");

        // openid 正常 → 受理成功
        when(userQueryPort.findOpenid(any())).thenReturn("openid-1");
        PayoutGateway.PayoutResult ok = service.apply("WD2", 100L, 1L, "测试");
        assertTrue(ok.accepted());
    }
}
