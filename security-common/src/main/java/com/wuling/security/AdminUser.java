package com.wuling.security;

/**
 * 当前登录管理端用户上下文（基于 ThreadLocal）。
 * 由 AdminAuthInterceptor 在请求进入时写入、请求结束时清理。
 *
 * <p>与 {@link CurrentUser}（小程序用户）分离，避免两套 token 体系混用同一个上下文。
 */
public final class AdminUser {

    private record Holder(Long userId, String username) {
    }

    private static final ThreadLocal<Holder> HOLDER = new ThreadLocal<>();

    private AdminUser() {
    }

    public static void set(Long userId, String username) {
        HOLDER.set(new Holder(userId, username));
    }

    public static Long getUserId() {
        Holder h = HOLDER.get();
        return h == null ? null : h.userId();
    }

    public static String getUsername() {
        Holder h = HOLDER.get();
        return h == null ? null : h.username();
    }

    public static Long require() {
        Long userId = getUserId();
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
