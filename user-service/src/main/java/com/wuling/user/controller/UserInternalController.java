package com.wuling.user.controller;

import com.wuling.user.entity.AppUser;
import com.wuling.user.service.MiniAppAuthService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户服务内部接口（第 14 期支付接入新增）。
 *
 * <p><b>用途</b>：微信支付 JSAPI 下单必须指定支付者 openid，
 * 而 openid 由用户服务在登录时从微信换取并落库。
 * 交易服务需要它，但不应直接读用户库（跨域），故走本内部接口。
 *
 * <p><b>为什么不让前端传 openid</b>：
 * 前端传入等于允许构造「用别人的 openid 下单」，
 * 属越权风险。openid 必须由服务端依据登录态（JWT 的 userId）查询。
 *
 * <p><b>暴露面</b>：{@code /internal/**} 不在网关路由范围内，
 * 且用户服务仅监听 127.0.0.1，因此不会被公网访问。
 */
@RestController
@RequestMapping("/internal/users")
public class UserInternalController {

    private final MiniAppAuthService authService;

    public UserInternalController(MiniAppAuthService authService) {
        this.authService = authService;
    }

    /**
     * 查询用户 openid。
     *
     * <p>刻意返回固定结构（含 userId 与是否有值），
     * 让调用方能区分「用户不存在」与「用户存在但无 openid」两种情况。
     */
    @GetMapping("/{userId}/openid")
    public Map<String, Object> openid(@PathVariable Long userId) {
        Map<String, Object> result = new HashMap<>();
        result.put("userId", userId);
        try {
            AppUser user = authService.requireUser(userId);
            result.put("openId", user.getOpenId());
            result.put("found", StringUtils.hasText(user.getOpenId()));
        } catch (Exception e) {
            // 用户不存在或查询失败：返回 found=false，由调用方决定如何处理
            result.put("openId", null);
            result.put("found", false);
        }
        return result;
    }
}
