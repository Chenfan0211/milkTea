package com.wuling.user.tongue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.user.config.TongueProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TongueDetectionClientTest {

    private static final String BASE_URL = "https://tongue.test";
    private static final String APP_KEY = "app-key";
    private static final String APP_SECRET = "test-secret";

    private MockRestServiceServer detectionServer;
    private MockRestServiceServer reportServer;
    private TongueDetectionClient client;
    private TongueSigner signer;

    @BeforeEach
    void setUp() {
        TongueProperties properties = new TongueProperties();
        properties.setAppKey(APP_KEY);
        properties.setAppSecret(APP_SECRET);
        signer = new TongueSigner(properties);

        RestClient.Builder detectionBuilder = RestClient.builder().baseUrl(BASE_URL);
        RestClient.Builder reportBuilder = RestClient.builder().baseUrl(BASE_URL);
        detectionServer = MockRestServiceServer.bindTo(detectionBuilder).build();
        reportServer = MockRestServiceServer.bindTo(reportBuilder).build();
        client = new TongueDetectionClient(detectionBuilder.build(), reportBuilder.build(), signer, new ObjectMapper());
    }

    @Test
    void detectionSendsFixedHeadersAndBodyWithoutAppId() {
        detectionServer.expect(requestTo(BASE_URL + "/api/tongueimage/detection"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(signedHeaders())
                .andExpect(jsonPath("$.userSex").value("2"))
                .andExpect(jsonPath("$.userAge").value(31))
                .andExpect(jsonPath("$.tongueOnImg").value("data:image/png;base64,AAAA"))
                .andRespond(withSuccess("""
                        {"status":0,"message":"ok","code":"0","data":{"reportId":"report-1"}}
                        """, MediaType.APPLICATION_JSON));

        TongueApiResponse response = client.detect("2", 31, "data:image/png;base64,AAAA");

        assertTrue(response.success());
        assertEquals("report-1", response.data().get("reportId").asText());
        detectionServer.verify();
    }

    @Test
    void reportFetchSignsUrlEncodedReportId() {
        String reportId = "R 1/中文";
        reportServer.expect(request -> {
                    assertTrue(request.getURI().toString().contains("reportId=R%201"));
                    HttpHeaders headers = request.getHeaders();
                    assertEquals(APP_KEY, headers.getFirst("appKey"));
                    assertEquals(APP_SECRET, headers.getFirst("appSecret"));
                    assertFalse(headers.containsKey("appId"));
                    assertEquals(signer.signGet(reportId).contentMd5(), headers.getFirst("contentMd5"));
                    assertEquals(signer.sign(headers.getFirst("timestamp"), headers.getFirst("nonce"),
                            headers.getFirst("contentMd5")).sign(), headers.getFirst("sign"));
                })
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"status":0,"message":"ok","code":"0","data":{"reportStatus":"READY"}}
                        """, MediaType.APPLICATION_JSON));

        TongueApiResponse response = client.fetchReport(reportId);

        assertTrue(response.success());
        assertEquals("READY", response.data().get("reportStatus").asText());
        reportServer.verify();
    }

    private RequestMatcher signedHeaders() {
        return request -> {
            HttpHeaders headers = request.getHeaders();
            assertEquals(APP_KEY, headers.getFirst("appKey"));
            assertEquals(APP_SECRET, headers.getFirst("appSecret"));
            assertFalse(headers.containsKey("appId"));
            assertTrue(headers.getFirst("timestamp").matches("\\d+"));
            assertFalse(headers.getFirst("nonce").contains("-"));
            assertEquals(signer.sign(headers.getFirst("timestamp"), headers.getFirst("nonce"),
                    headers.getFirst("contentMd5")).sign(), headers.getFirst("sign"));
        };
    }
}
