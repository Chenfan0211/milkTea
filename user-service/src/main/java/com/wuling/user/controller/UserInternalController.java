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

    /**
     * 查询用户会员等级代码（第 15 期：会员价后端重算）。
     *
     * <p><b>为什么交易服务必须回查这里，而不是用客户端传来的等级</b>：
     * 会员价直接影响实收金额，若以客户端传入的 {@code vipLevel} 为准，
     * 用户改包成 {@code Lv3} 即可自选 6 折 —— 属越权。
     * 因此等级一律以本服务落库的 {@code app_user.vip_level} 为权威来源。
     *
     * <p>返回值中 {@code found} 表示「用户存在且已设置等级」：
     * 交易服务据此区分「无等级（按原价）」与「查询失败（同样按原价但不视为正常）」。
     * 等级为空时不报错 —— 新注册用户默认 Lv1，但历史数据可能为空。
     */
    @GetMapping("/{userId}/vip-level")
    public Map<String, Object> vipLevel(@PathVariable Long userId) {
        Map<String, Object> result = new HashMap<>();
        result.put("userId", userId);
        try {
            AppUser user = authService.requireUser(userId);
            String level = user.getVipLevel();
            result.put("vipLevel", level);
            result.put("found", StringUtils.hasText(level));
        } catch (Exception e) {
            result.put("vipLevel", null);
            result.put("found", false);
        }
        return result;
    }
}
