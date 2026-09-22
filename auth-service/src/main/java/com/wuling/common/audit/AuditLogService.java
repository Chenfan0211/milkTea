package com.wuling.common.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 审计日志服务（P2 安全加固）。
 *
 * <p>背景：{@code audit_log} 表早已存在，但只有管理端一个手工写入口，
 * 关键的<b>资金与鉴权类操作</b>（支付回调、提现审核、登录失败）没有任何留痕，
 * 出问题后无法追溯"谁在什么时候做了什么"。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>写审计失败绝不影响主流程</b>：审计是旁路，抛异常会污染业务事务，故整体 try-catch；</li>
 *   <li>使用 JdbcTemplate 直接写入，不与业务事务强耦合；</li>
 *   <li>敏感值（口令、令牌、完整手机号）不写入，由调用方保证已脱敏。</li>
 * </ul>
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final JdbcTemplate jdbcTemplate;

    public AuditLogService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 写入一条审计记录。
     *
     * @param operator 操作者（用户名 / 用户 ID / SYSTEM）
     * @param module   模块（PAY / WITHDRAW / AUTH）
     * @param action   动作（CALLBACK / REVIEW / LOGIN_FAIL）
     * @param target   操作对象（订单号、提现单号等）
     * @param reason   备注 / 原因
     * @param ip       来源 IP
     */
    public void record(String operator, String module, String action, String target, String reason, String ip) {
        try {
            jdbcTemplate.update(
                    "insert into audit_log (operator, module, action, target, reason, ip) "
                            + "values (?, ?, ?, ?, ?, ?)",
                    truncate(operator, 64), truncate(module, 64), truncate(action, 64),
                    truncate(target, 255), truncate(reason, 255), truncate(ip, 64));
        } catch (Exception e) {
            // 审计失败不影响业务：只记 warn，不向上抛
            log.warn("write audit log failed, module={} action={} target={} err={}",
                    module, action, target, e.getMessage());
        }
    }

    /** 带变更前后快照的记录（用于审核类操作） */
    public void recordChange(String operator, String module, String action, String target,
                             String beforeValue, String afterValue, String reason, String ip) {
        try {
            jdbcTemplate.update(
                    "insert into audit_log (operator, module, action, target, before_value, after_value, reason, ip) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                    truncate(operator, 64), truncate(module, 64), truncate(action, 64), truncate(target, 255),
                    beforeValue, afterValue, truncate(reason, 255), truncate(ip, 64));
        } catch (Exception e) {
            log.warn("write audit log failed, module={} action={} target={} err={}",
                    module, action, target, e.getMessage());
        }
    }

    /** 按列宽截断，避免写入超长值导致整条记录失败 */
    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
