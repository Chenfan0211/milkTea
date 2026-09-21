package com.wuling.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.auth.security.MiniAppTokenProvider;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.user.dto.WxLoginResponse;
import com.wuling.user.entity.AppUser;
import com.wuling.user.mapper.AppUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wuling.common.redis.RedisManager;
import com.wuling.common.redis.RedisNamespace;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 小程序端登录与用户信息维护。
 *
 * 登录流程：wx.login code -> code2session -> openid -> 查/建 app_user -> 签发 JWT
 * session_key 存入 Redis（用于后续解密手机号/用户信息），不返回给前端。
 */
@Service
public class MiniAppAuthService {

    private static final Logger log = LoggerFactory.getLogger(MiniAppAuthService.class);
    // key 后缀：完整 key = RedisNamespace.AUTH 前缀 + 后缀
    private static final String SESSION_KEY_PREFIX = "wx:session:";
    private static final String LOCATION_KEY_PREFIX = "user:location:";

    private final WxAuthService wxAuthService;
    private final AppUserMapper appUserMapper;
    private final MiniAppTokenProvider tokenProvider;
    private final StringRedisTemplate redisTemplate;
    private final RedisManager redisManager;
    private final SmsCodeService smsCodeService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MiniAppAuthService(WxAuthService wxAuthService,
                              AppUserMapper appUserMapper,
                              MiniAppTokenProvider tokenProvider,
                              StringRedisTemplate redisTemplate,
                              RedisManager redisManager,
                              SmsCodeService smsCodeService) {
        this.wxAuthService = wxAuthService;
        this.appUserMapper = appUserMapper;
        this.tokenProvider = tokenProvider;
        this.redisTemplate = redisTemplate;
        this.redisManager = redisManager;
        this.smsCodeService = smsCodeService;
    }

    /** 登录态命名空间的模板（single 模式下位于 app.redis.database.auth 指定的库） */
    private StringRedisTemplate authRedis() {
        return redisManager.template(RedisNamespace.AUTH);
    }

    /** 拼接带命名空间前缀的完整 key */
    private String authKey(String suffix) {
        return RedisNamespace.AUTH.key(suffix);
    }

    /** 微信登录：换 openid，建立/复用用户，签发 token */
    @Transactional(rollbackFor = Exception.class)
    public WxLoginResponse login(String code) {
        WxAuthService.Session session = wxAuthService.code2Session(code);

        AppUser user = appUserMapper.selectOne(new LambdaQueryWrapper<AppUser>()
                .eq(AppUser::getOpenId, session.openId()));
        boolean newUser = false;
        if (user == null) {
            user = new AppUser();
            user.setOpenId(session.openId());
            user.setUnionId(session.unionId());
            user.setNickName("微信用户");
            user.setVipLevel("Lv1");
            user.setPoints(0L);
            user.setBalance(0L);
            user.setStatus(1);
            appUserMapper.insert(user);
            newUser = true;
            log.info("new app user created id={}", user.getId());
        } else if (session.unionId() != null && !session.unionId().equals(user.getUnionId())) {
            user.setUnionId(session.unionId());
            appUserMapper.updateById(user);
        }

        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(ResultCode.FORBIDDEN, "账号已停用");
        }

        // 缓存 session_key 供解密使用，不返回给前端
        if (StringUtils.hasText(session.sessionKey())) {
            try {
                authRedis().opsForValue().set(authKey(SESSION_KEY_PREFIX + user.getId()),
                        session.sessionKey(), Duration.ofHours(12));
            } catch (Exception e) {
                log.warn("cache session_key failed: {}", e.getMessage());
            }
        }

        WxLoginResponse response = new WxLoginResponse();
        response.setToken(tokenProvider.createToken(user.getId(), user.getOpenId()));
        response.setUserId(user.getId());
        response.setOpenId(user.getOpenId());
        response.setNewUser(newUser);
        response.setNickName(user.getNickName());
        response.setAvatar(user.getAvatar());
        response.setPhone(user.getPhone());
        return response;
    }

    /** 解密并更新手机号 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> bindPhone(Long userId, String encryptedData, String iv) {
        String sessionKey = getSessionKey(userId);
        String plain = wxAuthService.decrypt(sessionKey, encryptedData, iv);
        String phone = readField(plain, "phoneNumber");
        if (!StringUtils.hasText(phone)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "未获取到手机号");
        }
        AppUser user = requireUser(userId);
        user.setPhone(phone);
        appUserMapper.updateById(user);

        Map<String, Object> result = new HashMap<>();
        result.put("phone", phone);
        return result;
    }

    /** 解密并更新头像昵称 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> bindProfile(Long userId, String encryptedData, String iv) {
        String sessionKey = getSessionKey(userId);
        String plain = wxAuthService.decrypt(sessionKey, encryptedData, iv);
        String nickName = readField(plain, "nickName");
        String avatar = readField(plain, "avatarUrl");

        AppUser user = requireUser(userId);
        if (StringUtils.hasText(nickName)) {
            user.setNickName(nickName);
        }
        if (StringUtils.hasText(avatar)) {
            user.setAvatar(avatar);
        }
        appUserMapper.updateById(user);

        Map<String, Object> result = new HashMap<>();
        result.put("nickName", user.getNickName());
        result.put("avatar", user.getAvatar());
        return result;
    }

    /**
     * 更新定位。
     * 定位属于会话态（前端 Storage 维护），后端仅做归属校验并写入缓存，
     * 便于后续「就近门店推荐」在服务端复用，不污染用户主表。
     */
    public void updateLocation(Long userId, Double latitude, Double longitude, String address) {
        requireUser(userId);
        if (latitude == null || longitude == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "缺少经纬度");
        }
        Map<String, Object> location = new HashMap<>();
        location.put("latitude", latitude);
        location.put("longitude", longitude);
        location.put("address", address);
        location.put("updatedAt", System.currentTimeMillis());
        try {
            authRedis().opsForValue().set(authKey(LOCATION_KEY_PREFIX + userId),
                    objectMapper.writeValueAsString(location), Duration.ofDays(7));
        } catch (Exception e) {
            log.warn("cache location failed: {}", e.getMessage());
        }
    }

    public AppUser requireUser(Long userId) {
        AppUser user = appUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    private String getSessionKey(Long userId) {
        String sessionKey = null;
        try {
            sessionKey = authRedis().opsForValue().get(authKey(SESSION_KEY_PREFIX + userId));
        } catch (Exception e) {
            log.warn("read session_key failed: {}", e.getMessage());
        }
        if (!StringUtils.hasText(sessionKey)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "登录状态已失效，请重新登录");
        }
        return sessionKey;
    }

    private String readField(String json, String field) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.path(field).asText(null);
        } catch (Exception e) {
            return null;
        }
    }
    // ---------- 降级方案：短信验证码绑定手机号 ----------

    /** 校验短信验证码并绑定手机号 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> bindPhoneBySms(Long userId, String phone, String code) {
        smsCodeService.verify(phone, code);

        // 同一手机号不允许绑定到多个账号
        AppUser existing = appUserMapper.selectOne(new LambdaQueryWrapper<AppUser>()
                .eq(AppUser::getPhone, phone));
        if (existing != null && !existing.getId().equals(userId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该手机号已绑定其他账号");
        }

        AppUser user = requireUser(userId);
        user.setPhone(phone);
        appUserMapper.updateById(user);

        Map<String, Object> result = new HashMap<>();
        result.put("phone", phone);
        return result;
    }

    // ---------- 新版头像昵称（chooseAvatar / input type=nickname）----------

    /** 更新头像地址（chooseAvatar 选择后上传得到的 URL） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateAvatar(Long userId, String avatar) {
        if (!StringUtils.hasText(avatar)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "头像地址不能为空");
        }
        AppUser user = requireUser(userId);
        user.setAvatar(avatar);
        appUserMapper.updateById(user);

        Map<String, Object> result = new HashMap<>();
        result.put("avatar", avatar);
        return result;
    }

    /** 更新昵称（input type=nickname 提交） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateNickName(Long userId, String nickName) {
        if (!StringUtils.hasText(nickName)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "昵称不能为空");
        }
        AppUser user = requireUser(userId);
        user.setNickName(nickName.trim());
        appUserMapper.updateById(user);

        Map<String, Object> result = new HashMap<>();
        result.put("nickName", user.getNickName());
        return result;
    }
}

