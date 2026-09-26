package com.wuling.user.service;

import com.wuling.common.exception.BusinessException;
import com.wuling.common.redis.RedisManager;
import com.wuling.common.redis.RedisNamespace;
import com.wuling.user.entity.AppUser;
import com.wuling.user.mapper.AppUserMapper;
import com.wuling.security.MiniAppTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MiniAppAuthServiceTest {

    private static final String LOGIN_UNAVAILABLE = "登录服务暂时不可用，请稍后重试";

    private WxAuthService wxAuthService;
    private AppUserMapper appUserMapper;
    private MiniAppTokenProvider tokenProvider;
    private StringRedisTemplate authTemplate;
    private ValueOperations<String, String> valueOperations;
    private MiniAppAuthService service;

    @BeforeEach
    void setUp() {
        wxAuthService = mock(WxAuthService.class);
        appUserMapper = mock(AppUserMapper.class);
        tokenProvider = mock(MiniAppTokenProvider.class);
        authTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        RedisManager redisManager = mock(RedisManager.class);
        SmsCodeService smsCodeService = mock(SmsCodeService.class);

        when(redisManager.template(RedisNamespace.AUTH)).thenReturn(authTemplate);
        when(authTemplate.opsForValue()).thenReturn(valueOperations);
        service = new MiniAppAuthService(
                wxAuthService,
                appUserMapper,
                tokenProvider,
                authTemplate,
                redisManager,
                smsCodeService
        );
    }

    @Test
    @DisplayName("已注册登录写入 session_key 失败时必须抛业务异常且不签发 token")
    void registeredLoginFailsClosedWhenSessionKeyWriteFails() {
        AppUser user = registeredUser();
        when(wxAuthService.code2Session("code")).thenReturn(
                new WxAuthService.Session("openid-1", null, "session-key-1")
        );
        when(appUserMapper.selectOne(any())).thenReturn(user);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        BusinessException error = assertThrows(BusinessException.class, () -> service.login("code"));

        assertEquals(LOGIN_UNAVAILABLE, error.getMessage());
        verify(tokenProvider, never()).createToken(any(), anyString());
    }

    @Test
    @DisplayName("未注册登录写入注册上下文失败时必须抛业务异常且不返回 registerToken")
    void unregisteredLoginFailsClosedWhenRegisterContextWriteFails() {
        when(wxAuthService.code2Session("code")).thenReturn(
                new WxAuthService.Session("openid-new", null, "session-key-new")
        );
        when(appUserMapper.selectOne(any())).thenReturn(null);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        BusinessException error = assertThrows(BusinessException.class, () -> service.login("code"));

        assertEquals(LOGIN_UNAVAILABLE, error.getMessage());
        verify(tokenProvider, never()).createToken(any(), anyString());
    }

    @Test
    @DisplayName("微信未返回 session_key 时不得签发可登录 token")
    void loginWithoutSessionKeyFailsClosed() {
        when(wxAuthService.code2Session("code")).thenReturn(
                new WxAuthService.Session("openid-1", null, null)
        );

        BusinessException error = assertThrows(BusinessException.class, () -> service.login("code"));

        assertEquals(LOGIN_UNAVAILABLE, error.getMessage());
        verify(tokenProvider, never()).createToken(any(), anyString());
    }

    @Test
    @DisplayName("手机号注册成功后必须保存新用户 session_key 再返回 token")
    void phoneRegistrationStoresSessionKey() {
        String registerToken = "register-token";
        when(valueOperations.get("wuling:auth:wx:register:" + registerToken))
                .thenReturn("{\"openId\":\"openid-new\",\"sessionKey\":\"session-key-new\"}");
        when(wxAuthService.decrypt("session-key-new", "encrypted-data", "iv"))
                .thenReturn("{\"phoneNumber\":\"13800000000\"}");
        when(appUserMapper.selectOne(any())).thenReturn(null);
        when(appUserMapper.insert(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser user = invocation.getArgument(0);
            user.setId(9L);
            return 1;
        });
        when(tokenProvider.createToken(9L, "openid-new")).thenReturn("token-new");

        var response = service.registerByPhone(registerToken, "encrypted-data", "iv", null);

        assertEquals("token-new", response.getToken());
        verify(valueOperations).set(
                eq("wuling:auth:wx:session:9"),
                eq("session-key-new"),
                eq(Duration.ofHours(12))
        );
    }

    private AppUser registeredUser() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setOpenId("openid-1");
        user.setStatus(1);
        return user;
    }
}