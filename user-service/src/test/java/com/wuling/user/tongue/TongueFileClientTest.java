package com.wuling.user.tongue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TongueFileClientTest {

    private MockRestServiceServer server;
    private TongueFileClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://file.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TongueFileClient(builder.build());
    }

    @Test
    void uploadUsesFixedInternalMultipartEndpoint() {
        server.expect(requestTo("http://file.test/internal/tongue-images"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> assertTrue(request.getHeaders().getContentType()
                        .isCompatibleWith(MediaType.MULTIPART_FORM_DATA)))
                .andRespond(withSuccess("""
                        {"code":0,"message":"ok","data":{"storedName":"s1","mimeType":"image/png","size":3}}
                        """, MediaType.APPLICATION_JSON));

        TongueFileClient.StoredFile stored = client.upload(new MockMultipartFile(
                "file", "tongue.png", "image/png", new byte[]{1, 2, 3}));

        assertEquals("s1", stored.storedName());
        assertEquals("image/png", stored.mimeType());
        assertEquals(3, stored.size());
        server.verify();
    }

    @Test
    void downloadUsesStoredNameAndReturnsFileServiceContentType() {
        server.expect(requestTo("http://file.test/internal/tongue-images/s1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(new byte[]{1, 2, 3}, MediaType.IMAGE_PNG));

        TongueFileClient.ImageBytes image = client.download("s1");

        assertArrayEquals(new byte[]{1, 2, 3}, image.bytes());
        assertEquals("image/png", image.mimeType());
        server.verify();
    }

    @Test
    void deleteUsesFixedInternalEndpoint() {
        server.expect(requestTo("http://file.test/internal/tongue-images/s1"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("{\"code\":0,\"message\":\"ok\"}", MediaType.APPLICATION_JSON));

        client.delete("s1");

        server.verify();
    }
}
