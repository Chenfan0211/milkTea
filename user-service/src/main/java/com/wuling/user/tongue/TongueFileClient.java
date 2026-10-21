package com.wuling.user.tongue;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;

/**
 * file-service 舌象图片内部适配器。
 *
 * <p>固定契约：
 * POST /internal/tongue-images multipart file -&gt; data {storedName,mimeType,size}
 * GET  /internal/tongue-images/{storedName} -&gt; 二进制
 * DELETE /internal/tongue-images/{storedName} -&gt; Result
 */
@Component
public class TongueFileClient {

    private final RestClient restClient;

    public TongueFileClient(@Qualifier("tongueFileRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public StoredFile upload(MultipartFile file) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new TongueClientException("图片读取失败", e);
        }
        String filename = safeFilename(file.getOriginalFilename());
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(resolvePartMimeType(file.getContentType(), bytes)));
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new HttpEntity<>(resource, partHeaders));

        try {
            JsonNode root = restClient.post()
                    .uri("/internal/tongue-images")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode data = unwrapData(root);
            String storedName = text(data, "storedName", "stored_name");
            String mimeType = text(data, "mimeType", "mime_type");
            long size = data != null && data.has("size") ? data.get("size").asLong(bytes.length) : bytes.length;
            if (!StringUtils.hasText(storedName)) {
                throw new TongueClientException("图片保存失败");
            }
            return new StoredFile(storedName, StringUtils.hasText(mimeType) ? mimeType : "application/octet-stream", size);
        } catch (RestClientResponseException e) {
            throw new TongueClientException("图片保存失败，请稍后重试", e);
        } catch (TongueClientException e) {
            throw e;
        } catch (Exception e) {
            throw new TongueClientException("图片保存失败，请稍后重试", e);
        }
    }

    public ImageBytes download(String storedName) {
        if (!StringUtils.hasText(storedName)) {
            throw new TongueClientException("图片不存在");
        }
        try {
            ResponseEntity<byte[]> response = restClient.get()
                    .uri("/internal/tongue-images/{storedName}", storedName)
                    .retrieve()
                    .toEntity(byte[].class);
            byte[] bytes = response.getBody();
            if (bytes == null) {
                throw new TongueClientException("图片不存在");
            }
            MediaType contentType = response.getHeaders().getContentType();
            String mimeType = contentType == null || MediaType.APPLICATION_OCTET_STREAM.includes(contentType)
                    ? sniffMimeType(bytes)
                    : contentType.toString();
            return new ImageBytes(bytes, mimeType);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new TongueClientException("图片不存在", e);
            }
            throw new TongueClientException("图片读取失败，请稍后重试", e);
        } catch (TongueClientException e) {
            throw e;
        } catch (Exception e) {
            throw new TongueClientException("图片读取失败，请稍后重试", e);
        }
    }

    public void delete(String storedName) {
        if (!StringUtils.hasText(storedName)) {
            return;
        }
        try {
            JsonNode root = restClient.delete()
                    .uri("/internal/tongue-images/{storedName}", storedName)
                    .retrieve()
                    .body(JsonNode.class);
            assertResultOk(root);
        } catch (RestClientResponseException e) {
            throw new TongueClientException("图片删除失败", e);
        } catch (TongueClientException e) {
            throw e;
        } catch (Exception e) {
            throw new TongueClientException("图片删除失败", e);
        }
    }

    private static void assertResultOk(JsonNode root) {
        if (root == null || root.isNull()) {
            return;
        }
        JsonNode code = root.get("code");
        if (code == null || code.isNull()) {
            return;
        }
        String value = code.asText("").trim().toUpperCase(Locale.ROOT);
        if (!"0".equals(value) && !"200".equals(value) && !"SUCCESS".equals(value)) {
            throw new TongueClientException("图片删除失败");
        }
    }

    private static JsonNode unwrapData(JsonNode root) {
        if (root == null || root.isNull()) {
            throw new TongueClientException("图片保存失败");
        }
        JsonNode code = root.get("code");
        if (code != null && !code.isNull()) {
            String value = code.asText("").trim().toUpperCase(Locale.ROOT);
            if (!"0".equals(value) && !"200".equals(value) && !"SUCCESS".equals(value)) {
                throw new TongueClientException("图片保存失败");
            }
        }
        JsonNode data = root.get("data");
        if (data == null || data.isNull()) {
            throw new TongueClientException("图片保存失败");
        }
        return data;
    }

    private static String text(JsonNode node, String... names) {
        if (node == null) {
            return null;
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                return value.isTextual() ? value.asText() : value.toString();
            }
        }
        return null;
    }

    private static String resolvePartMimeType(String contentType, byte[] bytes) {
        return StringUtils.hasText(contentType) && contentType.startsWith("image/")
                ? contentType
                : sniffMimeType(bytes);
    }

    private static String sniffMimeType(byte[] bytes) {
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return MediaType.IMAGE_PNG_VALUE;
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return MediaType.IMAGE_JPEG_VALUE;
        }
        if (bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    private static String safeFilename(String originalFilename) {
        String filename = StringUtils.hasText(originalFilename) ? originalFilename : "tongue-image";
        filename = filename.replace('\\', '/');
        int index = filename.lastIndexOf('/');
        if (index >= 0) {
            filename = filename.substring(index + 1);
        }
        return StringUtils.hasText(filename) ? filename : "tongue-image";
    }

    public record StoredFile(String storedName, String mimeType, long size) {
    }

    public record ImageBytes(byte[] bytes, String mimeType) {
    }
}
