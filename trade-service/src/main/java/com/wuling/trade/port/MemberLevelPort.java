package com.wuling.trade.port;

/**
 * 会员等级与折扣查询端口（第 15 期：会员价后端重算）。
 *
 * <p><b>为什么要抽端口</b>：会员等级存在两个不属于 trade 域的地方 ——
 * <ul>
 *   <li>用户的等级代码在 user-service 的 {@code app_user.vip_level}；</li>
 *   <li>等级对应的折扣在 {@code member_level.discount}（随 subject/api 库）。</li>
 * </ul>
 * 沿用本模块既有约定（见 {@link ProductQueryPort}、{@link UserQueryPort}）：
 * trade 不直连他域数据，只依赖接口；当前可给本地/桩实现，
 * 将来换 Feign 时业务代码不变。
 *
 * <p><b>安全语义</b>：本端口返回的是<b>服务端权威等级</b>，
 * 用于校验前端传入的等级。前端传的值只作交叉校验，
 * 绝不作为计价的唯一依据（否则用户改包即可自选高等级折扣）。
 *
 * <p><b>失败语义</b>：查询失败返回 null，由调用方按「无等级、不打折」处理，
 * 不抛异常 —— 等级查询故障不应该导致整单无法下单。
 */
public interface MemberLevelPort {

    /**
     * 查询用户当前的会员等级代码（如 {@code Lv1}）。
     *
     * @param userId 用户 ID（取自 JWT）
     * @return 等级代码；用户不存在或未设置时返回 null
     */
    String findUserLevelCode(Long userId);

    /**
     * 查询等级对应的折扣配置原文。
     *
     * @param levelCode 等级代码，如 {@code Lv1}
     * @return 折扣配置，如 {@code 8折}；等级不存在时返回 null（按不打折处理）
     */
    String findLevelDiscount(String levelCode);
}