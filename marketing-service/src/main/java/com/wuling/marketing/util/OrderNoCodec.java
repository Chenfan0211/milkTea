package com.wuling.marketing.util;

import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 兼容 trade 订单号与 user_coupon.lock_order_id 的数字列。 */
public final class OrderNoCodec {

    private OrderNoCodec() {
    }

    /**
     * 将订单号转换为 lock_order_id。
     *
     * <p>历史纯数字订单保持原值；WX 前缀长订单号超过 long 时，使用 SHA-256
     * 稳定映射到正 long，避免 Overflow 后直接拒绝真实订单。
     */
    public static long toLockOrderId(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单号不能为空");
        }
        String normalized = orderNo.trim();
        String numeric = normalized.startsWith("WX") ? normalized.substring(2) : normalized;
        if (numeric.matches("\\d+")) {
            try {
                return Long.parseLong(numeric);
            } catch (NumberFormatException ignored) {
                // 超出 long 的代表性业务号走稳定哈希。
            }
        }
        return stablePositiveHash(normalized);
    }

    private static long stablePositiveHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            long hash = ByteBuffer.wrap(digest).getLong() & Long.MAX_VALUE;
            return hash == 0L ? 1L : hash;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}