package com.wuling.finance.port;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RemotePayoutAdapter} 单元测试：fail-closed 语义。
 *
 * <p>核心保障：出款 HTTP 调用出现任何异常，都必须返回 accepted=false，
 * 由调用方解冻金额 —— 绝不静默成功（否则「以为在打款，实际没打」属资损）。
 *
 * <p>正常解析路径（accepted / immediatePaid / batchNo）已由生产 mock 环境
 * 端到端实测覆盖（返回 immediatePaid=true），此处只锁定失败兜底。
 */
class RemotePayoutAdapterTest {

    @Test
    void transferFailsClosedOnDownstreamError() {
        RestClient restClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        when(restClient.post()).thenReturn(uriSpec);
        // uri() 这一步就抛异常，模拟下游不可用
        when(uriSpec.uri(anyString())).thenThrow(new RuntimeException("下游不可用"));

        RemotePayoutAdapter adapter = new RemotePayoutAdapter(restClient);
        PayoutPort.PayoutResult result = adapter.transfer("WD1", 500L, 7L, "测试");

        assertFalse(result.accepted(), "下游异常必须 fail-closed");
        assertFalse(result.immediatePaid());
        assertTrue(result.failReason().contains("下游不可用"), "失败原因要透传，便于排查");
    }
}
