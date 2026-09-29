package com.wuling.product.port;

/**
 * 门店经营者归属校验端口（小程序端门店选品鉴权用）。
 *
 * <p><b>为什么需要</b>：门店选品的读写接口由小程序门店店员调用，
 * {@code storeSubjectId} 由前端传入。若不校验，任何登录用户都能传任意
 * subjectId 修改他人门店选品 —— 属越权写操作。
 *
 * <p>归属关系（user_role_grant / app_user）归属 user / subject 域，
 * product 不应直接读它们的库，故走端口；当前由 server 的
 * {@code /internal/store-operator-check} 提供（与 trade 的
 * {@link com.wuling.trade.port.StoreOperatorPort} 同源）。
 *
 * <p><b>失败语义（关键）</b>：查询失败返回 {@code false}（拒绝）而非
 * {@code true}。选品是影响消费端可售商品的服务端写操作，校验不通过时
 * 必须失败关闭（fail-closed），绝不能因内网抖动而放行。
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
