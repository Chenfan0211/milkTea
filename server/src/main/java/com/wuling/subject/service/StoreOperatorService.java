package com.wuling.subject.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 「用户是否为某门店主体的有效经营者」判定。
 *
 * <p><b>为什么需要独立服务</b>：该判定是门店侧所有数据接口的越权防线
 * （核销、门店选品、门店设备与冲泡记录）。历史实现写在
 * {@code InternalQueryController#storeOperatorCheck} 里，只有内部接口能用；
 * 一旦 app 侧接口自行复制这段 SQL，两边口径就会漂移，
 * 出现「内部接口说有权、app 接口说无权」这类难排查的问题。
 * 因此把它收敛到本服务，内部接口与 app 接口共用同一份判定。
 *
 * <p><b>口径</b>：满足其一即视为有权经营：
 * <ol>
 *   <li>{@code user_role_grant} 中存在 (user_id, role_code=STORE, subject_id, status=active) 记录；</li>
 *   <li>{@code app_user.bound_subject_id = subjectId} 且 {@code business_role = 'store'}。</li>
 * </ol>
 * 第 2 条对 {@code business_role} 用 {@code UPPER} 归一，兼容历史小写存量
 * （见 V72 迁移与 BusinessRoleCaseMigrationTest）。
 * 与小程序 {@code /api/v1/app/roles/mine} 的判定口径保持一致。
 *
 * <p><b>失败口径</b>：参数缺失返回 false；查询异常直接抛出，
 * 让请求以失败结束而不是被误判为有权/无权。
 * 无论哪种情况都不会把他人门店的数据放出去。
 */
@Service
public class StoreOperatorService {

    /**
     * user_role_grant 与 app_user 归属 user-service，server 模块不依赖其 Java 实体，
     * 跨模块读表统一走 JdbcTemplate（与 AppRoleController 的做法一致）。
     */
    private final JdbcTemplate jdbcTemplate;

    public StoreOperatorService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 当前用户是否有权经营该门店主体。
     *
     * @param userId    小程序用户 ID（取自 JWT）
     * @param subjectId 门店主体 ID（biz_subject.id）
     * @return true=有权经营
     */
    public boolean canOperate(Long userId, Long subjectId) {
        if (userId == null || subjectId == null) {
            return false;
        }
        // 1) 授权表：显式授予且已生效
        Long granted = jdbcTemplate.queryForObject(
                "select count(*) from user_role_grant "
                        + "where user_id = ? and role_code = 'STORE' and subject_id = ? "
                        + "and status = 'active' and deleted = 0",
                Long.class, userId, subjectId);
        if (granted != null && granted > 0) {
            return true;
        }
        // 2) 当前绑定主体：business_role=STORE（UPPER 归一，兼容历史小写存量）且绑定到该门店
        Long bound = jdbcTemplate.queryForObject(
                "select count(*) from app_user "
                        + "where id = ? and bound_subject_id = ? and UPPER(business_role) = 'STORE' and deleted = 0",
                Long.class, userId, subjectId);
        return bound != null && bound > 0;
    }
}
