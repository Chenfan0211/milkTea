package com.wuling.common.subscribe;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订阅消息发送账本。
 *
 * <p>生产者在业务事务内先登记唯一 messageKey；消费者只负责发送并更新状态。
 * 这样重复支付回调、Outbox 重发和 MQ 重投都不会静默产生多条业务通知。</p>
 */
@Component
@ConditionalOnClass(JdbcTemplate.class)
public class SubscribeMessageLedger {

    private final JdbcTemplate jdbcTemplate;

    public SubscribeMessageLedger(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 在业务事务内登记唯一发送任务；返回 false 表示该业务通知已登记过。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean begin(String messageKey, String templateKey, Long userId) {
        int inserted = jdbcTemplate.update(
                "insert ignore into subscribe_message_log "
                        + "(message_key, template_key, user_id, status, attempts) "
                        + "values (?, ?, ?, 'PROCESSING', 0)",
                messageKey, templateKey, userId);
        return inserted == 1;
    }

    /** 是否已经到达终态，终态消息重复投递时直接 ACK。 */
    public boolean isTerminal(String messageKey) {
        try {
            String status = jdbcTemplate.queryForObject(
                    "select status from subscribe_message_log where message_key = ?",
                    String.class, messageKey);
            return "SENT".equals(status) || "SKIPPED".equals(status);
        } catch (EmptyResultDataAccessException e) {
            return false;
        }
    }

    public void markSent(String messageKey, int errcode, String errmsg) {
        mark(messageKey, "SENT", errcode, errmsg);
    }

    public void markSkipped(String messageKey, int errcode, String errmsg) {
        mark(messageKey, "SKIPPED", errcode, errmsg);
    }

    public void markFailed(String messageKey, int errcode, String errmsg) {
        mark(messageKey, "FAILED", errcode, errmsg);
    }

    private void mark(String messageKey, String status, int errcode, String errmsg) {
        String safeMessage = errmsg == null ? "" : errmsg;
        if (safeMessage.length() > 500) {
            safeMessage = safeMessage.substring(0, 500);
        }
        jdbcTemplate.update(
                "update subscribe_message_log set status = ?, errcode = ?, errmsg = ?, "
                        + "attempts = attempts + 1, "
                        + "sent_at = if(? = 'SENT', now(), sent_at), update_time = now() "
                        + "where message_key = ?",
                status, errcode, safeMessage, status, messageKey);
    }
}
