package com.wuling.user.controller;

import com.wuling.security.CurrentUser;
import com.wuling.common.api.Result;
import com.wuling.user.dto.WxDecryptRequest;
import com.wuling.user.dto.WxLoginRequest;
import com.wuling.user.dto.WxLoginResponse;
import com.wuling.user.entity.AppUser;
import com.wuling.user.dto.SmsBindRequest;
import com.wuling.user.dto.SmsSendRequest;
import com.wuling.user.service.MiniAppAuthService;
import com.wuling.user.service.SmsCodeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 小程序端登录与账号接口。
 *
 * 鉴权策略：
 * - /wx-login 公开（登录入口）；
 * - 其余接口需携带 JWT，userId 一律从 token 解析，不信任前端传入。
 */
@RestController
@RequestMapping("/api/v1/app/auth")
public class MiniAppAuthController {

    private final MiniAppAuthService miniAppAuthService;
    private final SmsCodeService smsCodeService;

    public MiniAppAuthController(MiniAppAuthService miniAppAuthService,
                                 SmsCodeService smsCodeService) {
        this.miniAppAuthService = miniAppAuthService;
        this.smsCodeService = smsCodeService;
    }

    /** 微信登录：wx.login 的 code 换取 token */
    @PostMapping("/wx-login")
    public Result<WxLoginResponse> wxLogin(@Valid @RequestBody WxLoginRequest request) {
        return Result.ok(miniAppAuthService.login(request.getCode()));
    }

    /** 当前登录用户资料 */
    @GetMapping("/me")
    public Result<AppUser> me() {
        return Result.ok(miniAppAuthService.requireUser(CurrentUser.require()));
    }

    /** 绑定手机号（解密 encryptedData） */
    @PostMapping("/phone")
    public Result<Map<String, Object>> bindPhone(@Valid @RequestBody WxDecryptRequest request) {
        return Result.ok(miniAppAuthService.bindPhone(
                CurrentUser.require(), request.getEncryptedData(), request.getIv()));
    }

    /** 更新头像昵称（解密 encryptedData） */
    @PostMapping("/profile")
    public Result<Map<String, Object>> bindProfile(@Valid @RequestBody WxDecryptRequest request) {
        return Result.ok(miniAppAuthService.bindProfile(
                CurrentUser.require(), request.getEncryptedData(), request.getIv()));
    }

    /** 上报定位（需登录，服务端仅做归属校验与缓存） */
    @PostMapping("/location")
    public Result<Void> updateLocation(@RequestBody Map<String, Object> payload) {
        miniAppAuthService.updateLocation(
                CurrentUser.require(),
                toDouble(payload.get("latitude")),
                toDouble(payload.get("longitude")),
                payload.get("address") == null ? null : String.valueOf(payload.get("address")));
        return Result.ok();
    }

    // ---------- 降级方案：短信验证码绑定手机号 ----------

    /** 发送验证码（限流：同号 60s、同号日 10 条、同 IP 日 30 条） */
    @PostMapping("/sms/send")
    public Result<Void> sendSmsCode(@Valid @RequestBody SmsSendRequest request,
                                    jakarta.servlet.http.HttpServletRequest httpRequest) {
        smsCodeService.send(request.getPhone(), clientIp(httpRequest));
        return Result.ok();
    }

    /** 校验验证码并绑定手机号 */
    @PostMapping("/sms/bind")
    public Result<Map<String, Object>> bindPhoneBySms(@Valid @RequestBody SmsBindRequest request) {
        return Result.ok(miniAppAuthService.bindPhoneBySms(
                CurrentUser.require(), request.getPhone(), request.getCode()));
    }

    // ---------- 新版头像昵称（chooseAvatar / input type=nickname）----------

    @PostMapping("/avatar")
    public Result<Map<String, Object>> updateAvatar(@RequestBody Map<String, Object> payload) {
        String avatar = payload.get("avatar") == null ? null : String.valueOf(payload.get("avatar"));
        return Result.ok(miniAppAuthService.updateAvatar(CurrentUser.require(), avatar));
    }

    @PostMapping("/nickname")
    public Result<Map<String, Object>> updateNickName(@RequestBody Map<String, Object> payload) {
        String nickName = payload.get("nickName") == null ? null : String.valueOf(payload.get("nickName"));
        return Result.ok(miniAppAuthService.updateNickName(CurrentUser.require(), nickName));
    }

    private String clientIp(jakarta.servlet.http.HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp;
        }
        return request.getRemoteAddr();
    }

    private Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

