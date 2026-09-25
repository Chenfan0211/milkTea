package com.wuling.trade.port;

/**
 * 门店经营者归属校验端口。
 *
 * <p><b>为什么需要单独一个端口</b>：小程序端门店核销必须确认
 * 「当前登录用户确实经营该门店」，否则任何登录用户都能传任意
 * {@code storeSubjectId} 核销他人门店订单 —— 属越权操作。
 *
 * <p>归属关系（user_role_grant / app_user）归属 user / subject 域，
 * trade 不应直接读它们的库，故走端口；当前由 server 的
 * {@code /internal/store-operator-check} 提供。
 *
 * <p><b>失败语义（关键）</b>：查询失败返回 {@code false}（拒绝）而非
 * {@code true}。核销是资金相关写操作，校验不通过时必须失败关闭
 * （fail-closed），绝不能因内网抖动而放行。
 */
public interface StoreOperatorPort {

    /**
     * 判断用户是否为指定门店主体的有效经营者。
     *
     * @param userId    小程序用户 ID（取自 JWT，绝不接受前端传入）
     * @param subjectId 门店主体 ID
     * @return true=有权经营；任何异常/查不到均返回 false
     */
    boolean isStoreOperator(Long userId, Long subjectId);
}