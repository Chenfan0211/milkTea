package com.wuling.trade.port;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 团长身份查询请求形态契约测试。
 *
 * <p>背景：server 端接口是 {@code GET /internal/user-leader?userId=}，
 * 而客户端曾误用路径参数 {@code /internal/user-leader/{userId}}，导致永远 404，
 * 被 fail-open 静默当成“非团长”，分销佣金全部错分给平台。
 */
class RemoteMallLeaderAdapterTest {

    @Test
    void resolvesLeaderUsingQueryParameterContract() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://server:8090");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RemoteMallLeaderAdapter adapter = new RemoteMallLeaderAdapter(builder.build());

        server.expect(requestTo(containsString("/internal/user-leader?userId=7")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"leader\":true,\"leaderUserId\":7,\"leaderSubjectId\":411}",
                        MediaType.APPLICATION_JSON));

        MallLeaderPort.LeaderInfo info = adapter.resolve(7L);

        server.verify();
        assertTrue(info.leader(), "团长身份必须被正确识别");
    }
}
