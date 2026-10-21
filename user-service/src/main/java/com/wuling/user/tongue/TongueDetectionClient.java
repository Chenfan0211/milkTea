package com.wuling.user.tongue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 舌象第三方服务客户端。
 *
 * <p>该客户端只负责协议组装、发送、响应解析。图片、base64、完整请求 body 和
 * appSecret 不写日志；异常也只记录 HTTP 状态等非敏感信息。
 */
@Component
public class TongueDetectionClient {

    private static final Logger log = LoggerFactory.getLogger(TongueDetectionClient.class);

    private static final String DETECTION_PATH = "/api/tongueimage/detection";
    private static final String REPORT_PATH = "/api/tongueimage/getReport";
    private static final Set<String> SUCCESS_VALUES = Set.of(
            "0", "1", "200", "SUCCESS", "SUCCEED", "OK", "TRUE"
    );

    private final RestClient detectionRestClient;
    private final RestClient reportRestClient;
    private final TongueSigner signer;
    private final ObjectMapper objectMapper;

    public TongueDetectionClient(@Qualifier("tongueDetectionRestClient") RestClient detectionRestClient,
                                 @Qualifier("tongueReportRestClient") RestClient reportRestClient,
                                 TongueSigner signer,
                                 ObjectMapper objectMapper) {
        this.detectionRestClient = detectionRestClient;
        this.reportRestClient = reportRestClient;
        this.signer = signer;
        this.objectMapper = objectMapper;
    }

    public TongueApiResponse detect(String userSex, int userAge, String tongueOnImg) {
        byte[] body;
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("userSex", userSex);
            request.put("userAge", userAge);
            request.put("tongueOnImg", tongueOnImg);
            body = objectMapper.writeValueAsBytes(request);
        } catch (Exception e) {
            throw new TongueClientException("舌象检测请求构建失败", e);
        }

        TongueSigner.SignedHeaders signed = signer.signPost(body);
        try {
            JsonNode root = detectionRestClient.post()
                    .uri(DETECTION_PATH)
                    .headers(headers -> applyHeaders(headers, signed))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return parse(root);
        } catch (RestClientResponseException e) {
            log.warn("舌象检测第三方 HTTP 异常 status={}", e.getStatusCode().value());
            throw new TongueClientException("舌象检测服务暂时不可用，请稍后重试", e);
        } catch (TongueClientException e) {
            throw e;
        } catch (Exception e) {
            log.warn("舌象检测第三方调用失败 type={}", e.getClass().getSimpleName());
            throw new TongueClientException("舌象检测服务暂时不可用，请稍后重试", e);
        }
    }

    public TongueApiResponse fetchReport(String reportId) {
        if (!StringUtils.hasText(reportId)) {
            throw new TongueClientException("报告编号不能为空");
        }
        TongueSigner.SignedHeaders signed = signer.signGet(reportId);
        try {
            JsonNode root = reportRestClient.get()
                    .uri(uriBuilder -> uriBuilder.path(REPORT_PATH)
                            .queryParam("reportId", reportId)
                            .build())
                    .headers(headers -> applyHeaders(headers, signed))
                    .retrieve()
                    .body(JsonNode.class);
            return parse(root);
        } catch (RestClientResponseException e) {
            log.warn("舌象报告查询第三方 HTTP 异常 status={}", e.getStatusCode().value());
            throw new TongueClientException("舌象报告服务暂时不可用，请稍后重试", e);
        } catch (TongueClientException e) {
            throw e;
        } catch (Exception e) {
            log.warn("舌象报告查询第三方调用失败 type={}", e.getClass().getSimpleName());
            throw new TongueClientException("舌象报告服务暂时不可用，请稍后重试", e);
        }
    }

    private static void applyHeaders(HttpHeaders headers, TongueSigner.SignedHeaders signed) {
        headers.set("appKey", signed.appKey());
        headers.set("appSecret", signed.appSecret());
        headers.set("timestamp", signed.timestamp());
        headers.set("nonce", signed.nonce());
        headers.set("contentMd5", signed.contentMd5());
        headers.set("sign", signed.sign());
        // appId 当前不参与鉴权，固定不下发。
    }

    private TongueApiResponse parse(JsonNode root) {
        if (root == null || root.isNull()) {
            return new TongueApiResponse(false, null, "第三方服务无响应", null, null);
        }
        JsonNode statusNode = presentNode(root.get("status"));
        JsonNode messageNode = presentNode(root.get("message"));
        JsonNode codeNode = presentNode(root.get("code"));
        JsonNode dataNode = presentNode(root.get("data"));

        String status = text(statusNode);
        String message = text(messageNode);
        String code = text(codeNode);
        boolean success;
        if (statusNode != null) {
            success = isSuccessValue(statusNode);
        } else if (codeNode != null) {
            success = isSuccessValue(codeNode);
        } else {
            success = dataNode != null;
        }
        return new TongueApiResponse(success, status, message, code, dataNode);
    }

    private static JsonNode presentNode(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node;
    }

    private static String text(JsonNode node) {
        if (node == null) {
            return null;
        }
        return node.isTextual() ? node.asText() : node.toString();
    }

    private static boolean isSuccessValue(JsonNode node) {
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            int value = node.asInt();
            return value == 0 || value == 1 || value == 200;
        }
        String value = node.asText("").trim().toUpperCase(Locale.ROOT);
        return SUCCESS_VALUES.contains(value);
    }
}
