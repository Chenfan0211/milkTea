package com.wuling.trade.pay.wxpay;

import java.util.Map;

/**
 * 微信交易状态 → 项目内部标准状态映射（第 14 期支付接入）。
 *
 * <p><b>为什么单独抽一个类</b>：状态映射是「协议适配」，
 * 未来微信新增状态时只改这里；同时它可被单元测试完全覆盖，不依赖网络。
 *
 * <p>{@code third_status} 始终保留微信原始值（如 {@code SUCCESS}），
 * 便于追溯与对账；{@code standard_status} 用本项目的口径供业务判断。
 */
public final class WxPayStatusMapper {

    /** 已支付 */
    public static final String STANDARD_PAID = "PAID";
    /** 支付中（未支付） */
    public static final String STANDARD_PAYING = "PAYING";
    /** 已关闭 */
    public static final String STANDARD_CLOSED = "CLOSED";
    /** 已退款 */
    public static final String STANDARD_REFUNDED = "REFUNDED";
    /** 支付失败 */
    public static final String STANDARD_FAILED = "FAILED";

    private static final Map<String, String> MAPPING = Map.of(
            "SUCCESS", STANDARD_PAID,
            "NOTPAY", STANDARD_PAYING,
            "USERPAYING", STANDARD_PAYING,
            "ACCEPT", STANDARD_PAYING,
            "CLOSED", STANDARD_CLOSED,
            "REVOKED", STANDARD_CLOSED,
            "REFUND", STANDARD_REFUNDED,
            "PAYERROR", STANDARD_FAILED
    );

    private WxPayStatusMapper() {
    }

    /**
     * 映射为标准状态。
     *
     * @param tradeState 微信 trade_state，如 SUCCESS
     * @return 标准状态；未知取值返回 {@code null}（调用方需按「未知」处理并告警，
     *         不要默认当成成功）
     */
    public static String toStandardStatus(String tradeState) {
        if (tradeState == null) {
            return null;
        }
        return MAPPING.get(tradeState.trim().toUpperCase());
    }

    /** 是否支付成功（唯一可触发入账的状态） */
    public static boolean isSuccess(String tradeState) {
        return STANDARD_PAID.equals(toStandardStatus(tradeState));
    }
}
