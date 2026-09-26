package com.wuling.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.security.MiniAppTokenProvider;
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
import java.util.UUID;

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
    // 未注册用户的一次性注册凭证 key 后缀
    private static final String REGISTER_KEY_PREFIX = "wx:register:";
    private static final Duration REGISTER_TOKEN_TTL = Duration.ofMinutes(30);
    private static final Duration SESSION_KEY_TTL = Duration.ofHours(12);
    private static final String LOGIN_UNAVAILABLE = "登录服务暂时不可用，请稍后重试";

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

        /** 微信登录：换 openid，查询用户；未注册不自动建号，返回注册凭证 */
    @Transactional(rollbackFor = Exception.class)
    public WxLoginResponse login(String code) {
        WxAuthService.Session session;
        try {
            session = wxAuthService.code2Session(code);
        } catch (BusinessException e) {
            // 业务异常原样抛出（由 GlobalExceptionHandler 转成业务错误，非 500）
            throw e;
        } catch (Exception e) {
            // 非预期异常必须落错误日志：否则只会看到 500，无法定位。
            log.error("wx-login code2Session unexpected failure", e);
            throw new BusinessException(ResultCode.ERROR, "微信登录失败，请稍后重试");
        }
        if (!StringUtils.hasText(session.sessionKey())) {
            throw new BusinessException(ResultCode.ERROR, LOGIN_UNAVAILABLE);
        }

        AppUser user;
        try {
            user = appUserMapper.selectOne(new LambdaQueryWrapper<AppUser>()
                    .eq(AppUser::getOpenId, session.openId()));
        } catch (Exception e) {
            // 数据库不可用 / 表结构异常：落详细日志，避免只暴露 500 无上下文。
            log.error("wx-login query app_user failed openId={}", maskOpenId(session.openId()), e);
            throw new BusinessException(ResultCode.ERROR, "登录服务暂时不可用，请稍后重试");
        }

        // 未注册：不再自动建号，返回「未注册」信号与一次性注册凭证，
        // 前端据此进入登录/注册流程（绑手机号建号）。
        if (user == null) {
            String registerToken = UUID.randomUUID().toString().replace("-", "");
            Map<String, String> context = new HashMap<>();
            context.put("openId", session.openId());
            if (session.unionId() != null) {
                context.put("unionId", session.unionId());
            }
            context.put("sessionKey", session.sessionKey());
            try {
                authRedis().opsForValue().set(authKey(REGISTER_KEY_PREFIX + registerToken),
                        objectMapper.writeValueAsString(context), REGISTER_TOKEN_TTL);
            } catch (Exception e) {
                log.error("cache register token failed openId={}", maskOpenId(session.openId()), e);
                throw new BusinessException(ResultCode.ERROR, LOGIN_UNAVAILABLE);
            }

            WxLoginResponse response = new WxLoginResponse();
            response.setRegistered(false);
            response.setRegisterToken(registerToken);
            response.setOpenId(session.openId());
            return response;
        }

        // 已注册：补齐 unionId 并签发 token
        if (session.unionId() != null && !session.unionId().equals(user.getUnionId())) {
            user.setUnionId(session.unionId());
            appUserMapper.updateById(user);
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(ResultCode.FORBIDDEN, "账号已停用");
        }

        // 缓存 session_key 供解密使用，不返回给前端；写入失败不得签发不可用 token。
        storeSessionKey(user.getId(), session.sessionKey());

        // 关键业务日志：登录成功（openid 仅记前 12 位，避免完整凭据落盘）
        log.info("微信登录成功 userId={} openId={}", user.getId(), maskOpenId(session.openId()));
        return buildLoginResponse(user, false);
    }

    /** 新用户通过微信手机号授权建号并登录（未注册态） */
    @Transactional(rollbackFor = Exception.class)
    public WxLoginResponse registerByPhone(String registerToken, String encryptedData, String iv, Long referrerId) {
        RegisterContext context = readRegisterContext(registerToken);
        if (!StringUtils.hasText(context.sessionKey())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "微信授权已失效，请重新登录");
        }
        String plain = wxAuthService.decrypt(context.sessionKey(), encryptedData, iv);
        String phone = readField(plain, "phoneNumber");
        if (!StringUtils.hasText(phone)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "未获取到手机号");
        }
        AppUser user = createRegisteredUser(context.openId(), context.unionId(), phone, referrerId);
        storeSessionKey(user.getId(), context.sessionKey());
        deleteRegisterToken(registerToken);
        return buildLoginResponse(user, true);
    }

    /** 新用户通过短信验证码建号并登录（未注册态） */
    @Transactional(rollbackFor = Exception.class)
    public WxLoginResponse registerBySms(String registerToken, String phone, String code, Long referrerId) {
        smsCodeService.verify(phone, code);
        RegisterContext context = readRegisterContext(registerToken);
        AppUser user = createRegisteredUser(context.openId(), context.unionId(), phone, referrerId);
        deleteRegisterToken(registerToken);
        return buildLoginResponse(user, true);
    }

    /**
     * 校验邀请人是否有效，无效则返回 null（视为无推荐关系）。
     *
     * <p>为什么要校验而不是直接落库：referrerId 来自客户端分享链接，
     * 属不可信输入。若不过滤，会出现「指向不存在用户」的脏数据，
     * 让后续按推荐人发放奖励时查无此人；也便于挡住明显的伪造值。
     *
     * <p>仅在无效时返回 null 而不抛异常：推荐关系属于附加信息，
     * 不应因为一个坏参数就让用户注册失败。
     */
    private Long resolveReferrerId(Long referrerId) {
        if (referrerId == null || referrerId <= 0) {
            return null;
        }
        AppUser referrer = appUserMapper.selectById(referrerId);
        if (referrer == null) {
            log.warn("邀请人不存在，忽略推荐关系 referrerId={}", referrerId);
            return null;
        }
        return referrerId;
    }
    private AppUser createRegisteredUser(String openId, String unionId, String phone, Long referrerId) {
        AppUser existingByPhone = appUserMapper.selectOne(new LambdaQueryWrapper<AppUser>()
                .eq(AppUser::getPhone, phone));
        if (existingByPhone != null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该手机号已绑定其他账号");
        }
        AppUser user = new AppUser();
        user.setOpenId(openId);
        user.setUnionId(unionId);
        user.setPhone(phone);
        user.setNickName("微信用户");
        user.setVipLevel("Lv1");
        user.setPoints(0L);
        user.setBalance(0L);
        user.setStatus(1);
        // 邀请人：来自分享链接的 referrerId。
        // 只接受「已存在的其他用户」：自己推荐自己、以及指向不存在用户的脏数据
        // 都会让推荐关系失去意义，直接按「无推荐人」处理，不因此阻塞注册。
        user.setReferrerId(resolveReferrerId(referrerId));
        try {
            appUserMapper.insert(user);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 幂等兜底：open_id 唯一键（uk_app_user_open_id）冲突说明该微信已建号，
            // 多为「重复点击授权 / 注册凭证复用 / 并发请求」导致。
            // 这里回查并直接登录，避免把 DuplicateKeyException 抛成 500。
            AppUser existingByOpenId = appUserMapper.selectOne(new LambdaQueryWrapper<AppUser>()
                    .eq(AppUser::getOpenId, openId));
            if (existingByOpenId == null) {
                // 唯一键冲突但不是 open_id（例如手机号），按业务错误回传，便于前端提示。
                log.warn("register conflict but user not found by openId openId={}", maskOpenId(openId));
                throw new BusinessException(ResultCode.BAD_REQUEST, "注册失败，请稍后重试");
            }
            log.info("register idempotent hit, reuse existing user id={} openId={}",
                    existingByOpenId.getId(), maskOpenId(openId));
            return existingByOpenId;
        }
        log.info("new app user registered id={} openId={}", user.getId(), maskOpenId(openId));
        return user;
    }

    private WxLoginResponse buildLoginResponse(AppUser user, boolean newUser) {
        WxLoginResponse response = new WxLoginResponse();
        try {
            response.setToken(tokenProvider.createToken(user.getId(), user.getOpenId()));
        } catch (Exception e) {
            // 签发失败多为 JWT 密钥问题；落日志后转业务错误，避免前端只见 500。
            log.error("wx-login createToken failed userId={}", user.getId(), e);
            throw new BusinessException(ResultCode.ERROR, "登录凭证签发失败，请稍后重试");
        }
        response.setUserId(user.getId());
        response.setOpenId(user.getOpenId());
        response.setNewUser(newUser);
        response.setRegistered(true);
        response.setNickName(user.getNickName());
        response.setAvatar(user.getAvatar());
        response.setPhone(user.getPhone());
        return response;
    }

    private void storeSessionKey(Long userId, String sessionKey) {
        if (!StringUtils.hasText(sessionKey)) {
            throw new BusinessException(ResultCode.ERROR, LOGIN_UNAVAILABLE);
        }
        try {
            authRedis().opsForValue().set(authKey(SESSION_KEY_PREFIX + userId),
                    sessionKey, SESSION_KEY_TTL);
        } catch (Exception e) {
            log.error("cache session_key failed userId={}", userId, e);
            throw new BusinessException(ResultCode.ERROR, LOGIN_UNAVAILABLE);
        }
    }

    private RegisterContext readRegisterContext(String registerToken) {
        if (!StringUtils.hasText(registerToken)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "缺少注册凭证");
        }
        String raw = null;
        try {
            raw = authRedis().opsForValue().get(authKey(REGISTER_KEY_PREFIX + registerToken));
        } catch (Exception e) {
            log.warn("read register token failed: {}", e.getMessage());
        }
        if (!StringUtils.hasText(raw)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "注册凭证已失效，请重新登录");
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            String openId = node.path("openId").asText(null);
            String unionId = node.path("unionId").asText(null);
            String sessionKey = node.path("sessionKey").asText(null);
            if (!StringUtils.hasText(openId)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "注册凭证已失效，请重新登录");
            }
            return new RegisterContext(openId, unionId, sessionKey);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "注册凭证已失效，请重新登录");
        }
    }

    private void deleteRegisterToken(String registerToken) {
        try {
            authRedis().delete(authKey(REGISTER_KEY_PREFIX + registerToken));
        } catch (Exception e) {
            log.warn("delete register token failed: {}", e.getMessage());
        }
    }

    private String maskOpenId(String openId) {
        return openId == null ? "-"
                : openId.substring(0, Math.min(12, openId.length())) + "...";
    }

    /** 未注册用户的一次性注册上下文 */
    private record RegisterContext(String openId, String unionId, String sessionKey) {
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

    /**
     * 统计当前用户邀请注册的人数（第 15 期：邀请关系落库后接真实数据）。
     *
     * <p>计数依据 {@code app_user.referrer_id}，与「分享链接带 referrerId、
     * 注册时落库」的链路一致。只统计未删除账号。
     */
    public long countReferrals(Long userId) {
        return appUserMapper.countByReferrer(userId);
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
