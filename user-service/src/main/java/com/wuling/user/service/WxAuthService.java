package com.wuling.user.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

/**
 * 微信小程序登录：调用 code2session 换取 openid / unionid。
 *
 * 安全说明：
 * - appSecret 通过环境变量 WX_APP_SECRET 注入，不写入仓库；
 * - 未配置 appSecret 时直接报错（不做静默降级），避免生产环境误用；
 * - 微信返回 errcode 一律转为业务异常，不泄露原始报文。
 */
@Service
public class WxAuthService {

    private static final Logger log = LoggerFactory.getLogger(WxAuthService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    private final String appId;
    private final String appSecret;
    private final String code2sessionUrl;

    public WxAuthService(@Value("${app.wx.app-id}") String appId,
                         @Value("${app.wx.app-secret:}") String appSecret,
                         @Value("${app.wx.code2session-url}") String code2sessionUrl,
                         @Value("${app.wx.require-secret:true}") boolean requireSecret) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.code2sessionUrl = code2sessionUrl;
        // P3 安全加固：启动期校验，避免带着未配置的环境对外服务。
        // 本地开发可在 application-dev.yml 设 app.wx.require-secret=false 跳过。
        if (requireSecret && !StringUtils.hasText(appSecret)) {
            throw new IllegalStateException(
                    "未配置微信小程序密钥 app.wx.app-secret（环境变量 WX_APP_SECRET）。"
                    + "小程序登录依赖该密钥换取 openid，缺失时无法登录，服务拒绝启动。"
                    + "本地开发如需跳过，请在 application-dev.yml 设置 app.wx.require-secret=false");
        }
    }

    /** 微信登录会话信息 */
    public record Session(String openId, String unionId, String sessionKey) {
    }

    /**
     * 用小程序 wx.login 返回的 code 换取会话。
     *
     * @param code wx.login() 返回的临时登录凭证
     */
    public Session code2Session(String code) {
        if (!StringUtils.hasText(appSecret)) {
            // 不静默降级：缺少密钥说明环境未配置完成，直接失败
            throw new BusinessException(ResultCode.ERROR,
                    "微信登录未配置：请设置环境变量 WX_APP_SECRET");
        }
        if (!StringUtils.hasText(code)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "缺少登录凭证 code");
        }

        String url = code2sessionUrl
                + "?appid=" + appId
                + "&secret=" + appSecret
                + "&js_code=" + code
                + "&grant_type=authorization_code";

        String body;
        try {
            body = restTemplate.getForObject(url, String.class);
        } catch (Exception e) {
            log.error("code2session request failed", e);
            throw new BusinessException(ResultCode.ERROR, "微信服务暂时不可用，请稍后重试");
        }

        try {
            JsonNode node = objectMapper.readTree(body);
            int errcode = node.path("errcode").asInt(0);
            if (errcode != 0) {
                log.warn("code2session failed errcode={} errmsg={}", errcode, node.path("errmsg").asText());
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "微信登录失败（" + errcode + "），请重试");
            }
            String openId = node.path("openid").asText(null);
            if (!StringUtils.hasText(openId)) {
                throw new BusinessException(ResultCode.ERROR, "微信登录失败：未返回 openid");
            }
            return new Session(openId, node.path("unionid").asText(null), node.path("session_key").asText(null));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("code2session parse failed", e);
            throw new BusinessException(ResultCode.ERROR, "微信登录响应解析失败");
        }
    }

    /**
     * 解密微信加密数据（手机号 / 用户信息）。
     * 使用 AES-128-CBC，key 为 session_key，iv 为接口返回的 iv。
     */
    public String decrypt(String sessionKey, String encryptedData, String iv) {
        try {
            byte[] keyBytes = java.util.Base64.getDecoder().decode(sessionKey);
            byte[] ivBytes = java.util.Base64.getDecoder().decode(iv);
            byte[] dataBytes = java.util.Base64.getDecoder().decode(encryptedData);

            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(keyBytes, "AES"),
                    new javax.crypto.spec.IvParameterSpec(ivBytes));
            byte[] result = cipher.doFinal(dataBytes);
            return new String(result, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("decrypt failed: {}", e.getMessage());
            throw new BusinessException(ResultCode.BAD_REQUEST, "微信数据解密失败");
        }
    }
}
