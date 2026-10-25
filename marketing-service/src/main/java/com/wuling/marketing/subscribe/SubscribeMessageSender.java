package com.wuling.marketing.subscribe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** 通用微信小程序订阅消息发送客户端。 */
@Component
public class SubscribeMessageSender {

    private static final Logger log = LoggerFactory.getLogger(SubscribeMessageSender.class);

    private final WxMarketingSubscribeProperties properties;
    private final MiniAppAccessTokenProvider tokenProvider;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public SubscribeMessageSender(WxMarketingSubscribeProperties properties,
                                  MiniAppAccessTokenProvider tokenProvider) {
        this(properties, tokenProvider, buildRestTemplate(properties.getHttpTimeoutMs()));
    }

    SubscribeMessageSender(WxMarketingSubscribeProperties properties,
                           MiniAppAccessTokenProvider tokenProvider,
                           RestTemplate restTemplate) {
        this.properties = properties;
        this.tokenProvider = tokenProvider;
        this.restTemplate = restTemplate;
    }

    public SendResult send(String openid,
                           WxMarketingSubscribeProperties.TemplateConfig config,
                           Map<String, Map<String, String>> data,
                           String page) {
        if (!StringUtils.hasText(openid) || config == null || !StringUtils.hasText(config.getTemplateId())) {
            return new SendResult(-1, "订阅消息配置或 openid 缺失");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("touser", openid);
        payload.put("template_id", config.getTemplateId());
        payload.put("data", data);
        String targetPage = buildPage(page == null ? config.getPage() : page);
        if (StringUtils.hasText(targetPage)) {
            payload.put("page", targetPage);
        }
        if (StringUtils.hasText(config.getMiniprogramState())) {
            payload.put("miniprogram_state", config.getMiniprogramState());
        }

        SendResult first = post(payload);
        if (first.errcode() == 40001) {
            tokenProvider.invalidate();
            return post(payload);
        }
        return first;
    }

    private SendResult post(Map<String, Object> payload) {
        try {
            String accessToken = tokenProvider.getAccessToken();
            if (!StringUtils.hasText(accessToken)) {
                return new SendResult(-1, "empty access_token");
            }
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String url = properties.getSubscribeSendUrl() + "?access_token=" + accessToken;
            ResponseEntity<String> response = restTemplate.postForEntity(
                    url, new HttpEntity<>(objectMapper.writeValueAsString(payload), headers), String.class);
            JsonNode node = objectMapper.readTree(response.getBody());
            return new SendResult(node.path("errcode").asInt(0), node.path("errmsg").asText(""));
        } catch (Exception e) {
            return new SendResult(-1, sanitize(e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private static String buildPage(String page) {
        if (!StringUtils.hasText(page)) {
            return "";
        }
        return page.trim().replaceFirst("^/+", "");
    }

    private static String sanitize(String message) {
        if (!StringUtils.hasText(message)) {
            return "";
        }
        return message.replaceAll("access_token=[^&\\s]+", "access_token=***")
                .replaceAll("secret=[^&\\s]+", "secret=***");
    }

    private static RestTemplate buildRestTemplate(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeout = timeoutMs > 0 ? timeoutMs : 3000;
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return new RestTemplate(factory);
    }

    public record SendResult(int errcode, String errmsg) {
    }
}
