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

    @Test
    @DisplayName("资料更新：姓名/性别/生日/详细地址一次性写入并返回")
    void updateProfileFieldsPersistsAllFields() {
        AppUser user = registeredUser();
        when(appUserMapper.selectById(1L)).thenReturn(user);

        var result = service.updateProfileFields(1L, "李小茶", "female", "2000-01-02", "上海市浦东新区世纪大道 100 号");

        assertEquals("李小茶", user.getNickName());
        assertEquals("female", user.getGender());
        assertEquals(java.time.LocalDate.of(2000, 1, 2), user.getBirthday());
        assertEquals("上海市浦东新区世纪大道 100 号", user.getAddress());
        assertEquals("李小茶", result.get("nickName"));
        assertEquals("2000-01-02", result.get("birthday"));
        verify(appUserMapper).updateById(user);
    }

    @Test
    @DisplayName("资料更新：姓名必填")
    void updateProfileFieldsRejectsBlankNickName() {
        AppUser user = registeredUser();
        when(appUserMapper.selectById(1L)).thenReturn(user);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.updateProfileFields(1L, "  ", "male", "2000-01-02", ""));
        assertEquals("请输入您的姓名", error.getMessage());
        verify(appUserMapper, never()).updateById(any(AppUser.class));
    }

    @Test
    @DisplayName("资料更新：生日必填")
    void updateProfileFieldsRequiresBirthday() {
        AppUser user = registeredUser();
        when(appUserMapper.selectById(1L)).thenReturn(user);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.updateProfileFields(1L, "李小茶", "male", "", ""));
        assertEquals("请选择您的生日", error.getMessage());
        verify(appUserMapper, never()).updateById(any(AppUser.class));
    }

    @Test
    @DisplayName("资料更新：生日一旦已填写不可修改")
    void updateProfileFieldsRejectsBirthdayChange() {
        AppUser user = registeredUser();
        user.setBirthday(java.time.LocalDate.of(1999, 5, 6));
        when(appUserMapper.selectById(1L)).thenReturn(user);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.updateProfileFields(1L, "李小茶", "male", "2000-01-02", ""));
        assertEquals("生日填写后不可修改", error.getMessage());
        verify(appUserMapper, never()).updateById(any(AppUser.class));
    }

    @Test
    @DisplayName("资料更新：生日与已填一致时允许保存其他字段")
    void updateProfileFieldsAllowsSameBirthday() {
        AppUser user = registeredUser();
        user.setBirthday(java.time.LocalDate.of(2000, 1, 2));
        when(appUserMapper.selectById(1L)).thenReturn(user);

        service.updateProfileFields(1L, "李小茶", "male", "2000-01-02", "北京市朝阳区");

        assertEquals("北京市朝阳区", user.getAddress());
        verify(appUserMapper).updateById(user);
    }

    @Test
    @DisplayName("资料更新：非法性别被拒绝")
    void updateProfileFieldsRejectsInvalidGender() {
        AppUser user = registeredUser();
        when(appUserMapper.selectById(1L)).thenReturn(user);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.updateProfileFields(1L, "李小茶", "unknown", "2000-01-02", ""));
        assertEquals("性别取值不合法", error.getMessage());
        verify(appUserMapper, never()).updateById(any(AppUser.class));
    }

    @Test
    @DisplayName("资料更新：生日格式非法被拒绝")
    void updateProfileFieldsRejectsBadBirthdayFormat() {
        AppUser user = registeredUser();
        when(appUserMapper.selectById(1L)).thenReturn(user);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.updateProfileFields(1L, "李小茶", "male", "2000/01/02", ""));
        assertEquals("生日格式不正确", error.getMessage());
        verify(appUserMapper, never()).updateById(any(AppUser.class));
    }

    private AppUser registeredUser() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setOpenId("openid-1");
        user.setStatus(1);
        return user;
    }
}