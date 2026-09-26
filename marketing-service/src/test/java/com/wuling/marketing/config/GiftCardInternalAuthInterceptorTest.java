package com.wuling.marketing.config;

import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.security.MiniAppAuthInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GiftCardInternalAuthInterceptorTest {

    private static final String TOKEN = "svc-token";

    @Mock
    private MiniAppAuthInterceptor miniAppAuthInterceptor;
    @Mock
    private GiftCardInternalAuthInterceptor giftCardInternalAuthInterceptor;
    @Mock
    private org.springframework.web.servlet.config.annotation.InterceptorRegistry registry;
    @Mock
    private org.springframework.web.servlet.config.annotation.InterceptorRegistration giftCardRegistration;
    @Mock
    private org.springframework.web.servlet.config.annotation.InterceptorRegistration miniAppRegistration;

    @Test
    @DisplayName("正确服务令牌通过，允许 trade-service 调用礼品卡内部接口")
    void acceptsConfiguredToken() {
        GiftCardInternalAuthInterceptor interceptor = new GiftCardInternalAuthInterceptor("  " + TOKEN + "  ");

        assertDoesNotThrow(() -> interceptor.preHandle(
                requestWithToken(TOKEN),
                new MockHttpServletResponse(),
                new RequestMappingHandlerMapping()));
    }

    @Test
    @DisplayName("缺失、空白、错误令牌均按未授权拒绝")
    void rejectsMissingBlankAndWrongToken() {
        GiftCardInternalAuthInterceptor interceptor = new GiftCardInternalAuthInterceptor(TOKEN);

        for (String presented : Arrays.asList(null, "", "   ", "wrong-token")) {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> interceptor.preHandle(
                            requestWithToken(presented),
                            new MockHttpServletResponse(),
                            new RequestMappingHandlerMapping()));
            assertEquals(ResultCode.UNAUTHORIZED, exception.getCode());
        }
    }

    @Test
    @DisplayName("内部服务令牌未配置时拒绝所有礼品卡内部请求，避免裸奔")
    void rejectsWhenTokenIsNotConfigured() {
        GiftCardInternalAuthInterceptor interceptor = new GiftCardInternalAuthInterceptor("   ");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> interceptor.preHandle(
                        requestWithToken(TOKEN),
                        new MockHttpServletResponse(),
                        new RequestMappingHandlerMapping()));

        assertEquals(ResultCode.UNAUTHORIZED, exception.getCode());
    }

    @Test
    @DisplayName("令牌拦截器只注册礼品卡内部路径，不覆盖储值等存量内部接口")
    void registersOnlyGiftCardInternalPaths() {
        when(registry.addInterceptor(giftCardInternalAuthInterceptor)).thenReturn(giftCardRegistration);
        when(registry.addInterceptor(miniAppAuthInterceptor)).thenReturn(miniAppRegistration);
        when(giftCardRegistration.addPathPatterns(any(String[].class))).thenReturn(giftCardRegistration);
        when(miniAppRegistration.addPathPatterns(any(String[].class))).thenReturn(miniAppRegistration);

        new MarketingSecurityConfig(miniAppAuthInterceptor, giftCardInternalAuthInterceptor)
                .addInterceptors(registry);

        ArgumentCaptor<String[]> giftPaths = ArgumentCaptor.forClass(String[].class);
        verify(giftCardRegistration).addPathPatterns(giftPaths.capture());
        assertArrayEquals(new String[]{"/internal/gift-card-orders/**"}, giftPaths.getValue());

        ArgumentCaptor<String[]> miniAppPaths = ArgumentCaptor.forClass(String[].class);
        verify(miniAppRegistration).addPathPatterns(miniAppPaths.capture());
        assertFalse(Arrays.stream(miniAppPaths.getValue())
                .anyMatch(path -> path.startsWith("/internal/")));
    }

    private MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/gift-card-orders/GC1");
        if (token != null) {
            request.addHeader(GiftCardInternalAuthInterceptor.HEADER_NAME, token);
        }
        return request;
    }
}