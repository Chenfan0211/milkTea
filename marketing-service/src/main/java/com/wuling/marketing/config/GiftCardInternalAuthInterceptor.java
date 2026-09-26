package com.wuling.marketing.config;

import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 仅保护礼品卡内部接口的服务间令牌校验。 */
@Component
public class GiftCardInternalAuthInterceptor implements HandlerInterceptor {

    public static final String HEADER_NAME = "X-Internal-Token";

    private final String serviceToken;

    public GiftCardInternalAuthInterceptor(
            @Value("${app.internal.service-token:}") String serviceToken) {
        this.serviceToken = serviceToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String configured = serviceToken == null ? "" : serviceToken.trim();
        String presented = request.getHeader(HEADER_NAME);
        if (!StringUtils.hasText(configured) || !StringUtils.hasText(presented)
                || !MessageDigest.isEqual(
                        configured.getBytes(StandardCharsets.UTF_8),
                        presented.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "内部服务令牌未配置或无效");
        }
        return true;
    }
}
