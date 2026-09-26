package com.wuling.file.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.wuling.security.AdminAuthInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.cloud.nacos.discovery.enabled=false")
@AutoConfigureMockMvc
class FileControllerMvcTest {

    private static final String JWT_SECRET =
            "ZGV2LW9ubHktc2VjcmV0LWRvLW5vdC11c2UtaW4tcHJvZHVjdGlvbi0xMjM0NTY3OA==";
    private static final Path STORAGE_ROOT = createTempDirectory();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AdminAuthInterceptor adminAuthInterceptor;

    @DynamicPropertySource
    static void fileProperties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> JWT_SECRET);
        registry.add("app.file.storage-root", STORAGE_ROOT::toString);
        registry.add("app.file.public-base-url", () -> "/api/v1/files/public");
        registry.add("spring.servlet.multipart.max-file-size", () -> "5MB");
        registry.add("spring.servlet.multipart.max-request-size", () -> "6MB");
    }

    @Test
    void adminAuthInterceptorIsAvailableInApplicationContext() {
        assertNotNull(adminAuthInterceptor);
    }

    @Test
    void uploadWithoutAdminTokenIsUnauthorized() throws Exception {
        mockMvc.perform(multipart("/api/v1/files/images")
                        .file(imagePart()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void legacyValidateWithoutAdminTokenRemainsUnauthorized() throws Exception {
        mockMvc.perform(multipart("/api/v1/files/validate")
                        .file(imagePart()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void serviceInfoAndPublicImageReadDoNotRequireToken() throws Exception {
        mockMvc.perform(get("/api/v1/files/service-info"))
                .andExpect(status().isOk());

        String storedName = "0123456789abcdef0123456789abcdef.png";
        Files.write(STORAGE_ROOT.resolve(storedName), pngBytes(12, 12));

        mockMvc.perform(get("/api/v1/files/public/" + storedName))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void authorizedUploadReturnsStoredMetadataAndPublicReadWorks() throws Exception {
        byte[] png = pngBytes(24, 16);
        MvcResult result = mockMvc.perform(multipart("/api/v1/files/images")
                        .file(new MockMultipartFile("file", "photo.png", "image/png", png))
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.storedName").isString())
                .andExpect(jsonPath("$.data.url").isString())
                .andExpect(jsonPath("$.data.mimeType").value("image/png"))
                .andExpect(jsonPath("$.data.size").isNumber())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        mockMvc.perform(get(json.get("data").get("url").asText()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void authorizedFakeImageIsRejected() throws Exception {
        MockMultipartFile fake = new MockMultipartFile(
                "file", "fake.jpg", "image/jpeg", "not an image".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/files/images")
                        .file(fake)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isBadRequest());
    }

    private static MockMultipartFile imagePart() throws IOException {
        return new MockMultipartFile("file", "photo.png", "image/png", pngBytes(8, 8));
    }

    private static byte[] pngBytes(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.GREEN);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static String adminToken() {
        byte[] keyBytes = Base64.getDecoder().decode(JWT_SECRET);
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject("1")
                .claim("type", "access")
                .claim("username", "admin")
                .issuedAt(new Date(now))
                .expiration(new Date(now + 60_000))
                .signWith(Keys.hmacShaKeyFor(keyBytes))
                .compact();
    }

    private static Path createTempDirectory() {
        try {
            return Files.createTempDirectory("wuling-file-controller-");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}