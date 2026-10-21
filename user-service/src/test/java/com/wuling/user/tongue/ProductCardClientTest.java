package com.wuling.user.tongue;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商品卡内部客户端：正常解析、异常降级、空名称短路。
 *
 * <p>用 JDK 自带的 HttpServer 起真实回环服务，避免依赖 MockRestServiceServer
 * 与 Spring Framework 版本的绑定关系，同时覆盖真实 HTTP 编解码路径。
 */
class ProductCardClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    @DisplayName("正常返回时按归一化商品名建立索引")
    void indexesCardsByNormalizedName() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        String body = """
                {"code":0,"data":[
                  {"productId":"herbal-001","name":"桂香暖润茶","image":"/assets/images/3x/menu-product.jpg",
                   "price":1290,"originalPrice":1500,"tags":["年度热销","草本养生"]},
                  {"productId":"leaf-001","name":"滇绿","image":"/assets/images/3x/menu-product.jpg",
                   "price":990,"originalPrice":990,"tags":null}
                ],"message":"ok"}
                """;
        start(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, body);
        });

        Map<String, ProductCard> cards = client().cardsByNames(List.of(" 桂香暖润茶 ", "滇绿"));

        assertEquals(1, requests.get());
        ProductCard card = cards.get("桂香暖润茶");
        assertEquals("herbal-001", card.productId());
        assertEquals("/assets/images/3x/menu-product.jpg", card.image());
        assertEquals(1290L, card.price());
        assertEquals(1500L, card.originalPrice());
        assertEquals(List.of("年度热销", "草本养生"), card.tags());
        assertEquals(990L, cards.get("滇绿").price());
        assertEquals(List.of(), cards.get("滇绿").tags(), "tags 为 null 时按空标签处理");
    }

    @Test
    @DisplayName("商品服务报错时降级为空结果，不抛异常")
    void degradesWhenProductServiceFails() throws Exception {
        start(exchange -> respond(exchange, 500, "{\"code\":500,\"message\":\"boom\"}"));

        Map<String, ProductCard> cards = client().cardsByNames(List.of("桂香暖润茶"));

        assertTrue(cards.isEmpty(), "商品服务异常必须降级为空结果，不能阻断舌象报告");
    }

    @Test
    @DisplayName("商品名全为空时不发起请求")
    void skipsBlankNames() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        start(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, "{\"code\":0,\"data\":[]}");
        });

        Map<String, ProductCard> cards = client().cardsByNames(java.util.Arrays.asList("  ", null));

        assertTrue(cards.isEmpty());
        assertEquals(0, requests.get());
        assertNull(cards.get("anything"));
    }

    private ProductCardClient client() {
        return new ProductCardClient(RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .build());
    }

    private void start(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/product/cards", handler);
        server.start();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}