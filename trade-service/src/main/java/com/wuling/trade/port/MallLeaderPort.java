package com.wuling.trade.port;

/**
 * 团长身份与归属查询（trade 商城下单定价用）。
 *
 * <p>团长及其归属均由运营后台配置：
 * <ul>
 *   <li>团长身份：user_role_grant role_code=LEADER（active）；</li>
 *   <li>普通用户归属：user_leader（user_id -> leader_user_id，一个用户最多一个团长）。</li>
 * </ul>
 *
 * <p>定价口径（商城订单）：
 * <ul>
 *   <li>下单用户是团长 → SKU 售价（不再叠加分销佣金，也不给上级团长分佣，以其团长身份为准）；</li>
 *   <li>非团长 → SKU 售价 + 分销佣金，佣金在订单完成后归其归属团长；无归属则并入平台尾差。</li>
 * </ul>
 */
public interface MallLeaderPort {

    /** 团长解析结果 */
    record LeaderInfo(boolean leader, Long leaderUserId, Long leaderSubjectId) {
        public static LeaderInfo none() {
            return new LeaderInfo(false, null, null);
        }
    }

    /**
     * 解析用户团长身份与归属。
     *
     * @return leader=true 时 leaderUserId/leaderSubjectId 为团长自身主体（审计用）；
     *         leader=false 时 leaderUserId/leaderSubjectId 为其归属团长（可能为 null）。
     */
    LeaderInfo resolve(Long userId);
}