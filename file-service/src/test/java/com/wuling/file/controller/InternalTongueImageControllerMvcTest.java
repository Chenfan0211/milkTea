package com.wuling.file.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.cloud.nacos.discovery.enabled=false")
@AutoConfigureMockMvc
class InternalTongueImageControllerMvcTest {

    private static final Path STORAGE_ROOT = createTempDirectory();

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.file.storage-root", STORAGE_ROOT::toString);
        registry.add("app.file.public-base-url", () -> "/api/v1/files/public");
        registry.add("app.jwt.secret", () -> "ZGV2LW9ubHktc2VjcmV0LWRvLW5vdC11c2UtaW4tcHJvZHVjdGlvbi0xMjM0NTY3OA==");
    }

    @Test
    void uploadReadAndCompensatingDeleteShouldWork() throws Exception {
        MvcResult upload = mockMvc.perform(multipart("/internal/tongue-images")
                        .file(new MockMultipartFile("file", "tongue.png", "image/png", pngBytes(16, 16))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.storedName").isString())
                .andExpect(jsonPath("$.data.mimeType").value("image/png"))
                .andReturn();

        String body = upload.getResponse().getContentAsString();
        String storedName = body.replaceAll(".*\"storedName\":\"([^\"]+)\".*", "$1");
        assertThat(storedName).matches("[0-9a-f]{32}\\.png");

        mockMvc.perform(get("/internal/tongue-images/{storedName}", storedName))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"));

        mockMvc.perform(delete("/internal/tongue-images/{storedName}", storedName))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/internal/tongue-images/{storedName}", storedName))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidPathAndNonImageShouldBeRejected() throws Exception {
        mockMvc.perform(get("/internal/tongue-images/{storedName}", "secret.txt"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(multipart("/internal/tongue-images")
                        .file(new MockMultipartFile("file", "not-image.txt", "text/plain", "hello".getBytes())))
                .andExpect(status().isBadRequest());
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

    private static Path createTempDirectory() {
        try {
            return Files.createTempDirectory("tongue-file-mvc-test");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
