package com.wuling.security;

/**
 * 当前登录小程序用户上下文（基于 ThreadLocal）。
 * 由 MiniAppAuthInterceptor 在请求进入时写入、请求结束时清理。
 */
public final class CurrentUser {

    private static final ThreadLocal<Long> HOLDER = new ThreadLocal<>();

    private CurrentUser() {
    }

    public static void set(Long userId) {
        HOLDER.set(userId);
    }

    public static Long get() {
        return HOLDER.get();
    }

    public static Long require() {
        Long userId = HOLDER.get();
        if (userId == null) {
            throw new com.wuling.common.exception.BusinessException(
                    com.wuling.common.api.ResultCode.UNAUTHORIZED, "未登录或登录已过期");
        }
        return userId;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
