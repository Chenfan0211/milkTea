package com.wuling.common.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * event_outbox 的数据访问层。
 *
 * <p>所有写操作都不自行开启或提交事务。事务边界由 {@link OutboxService}
 * 与业务调用方决定，确保 outbox 记录和业务数据处于同一个本地事务。</p>
 */
@Repository
@ConditionalOnClass(JdbcTemplate.class)
public class EventOutboxMapper {

    private static final String COLUMNS = "id, event_id, aggregate_type, aggregate_id, "
            + "event_type, routing_key, biz_key, payload, status, retry_count, "
            + "next_retry_at, locked_by, locked_at, last_error, available_at, "
            + "create_time, update_time, sent_at";

    private static final RowMapper<EventOutboxEntity> ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

    private final JdbcTemplate jdbcTemplate;

    public EventOutboxMapper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    /**
     * 在调用方事务内插入一条 outbox 事件。
     */
    public int insert(EventOutboxEntity event) {
        String sql = "INSERT INTO event_outbox ("
                + "event_id, aggregate_type, aggregate_id, event_type, routing_key, "
                + "biz_key, payload, status, retry_count, next_retry_at, locked_by, "
                + "locked_at, last_error, available_at, create_time, update_time, sent_at"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        return jdbcTemplate.update(sql,
                event.eventId(),
                event.aggregateType(),
                event.aggregateId(),
                event.eventType(),
                event.routingKey(),
                event.bizKey(),
                event.payload(),
                event.status(),
                event.retryCount(),
                toTimestamp(event.nextRetryAt()),
                event.lockedBy(),
                toTimestamp(event.lockedAt()),
                event.lastError(),
                toTimestamp(event.availableAt()),
                toTimestamp(event.createTime()),
                toTimestamp(event.updateTime()),
                toTimestamp(event.sentAt()));
    }

    /**
     * 使用 SELECT ... FOR UPDATE SKIP LOCKED 在数据库中原子认领一批事件。
     *
     * <p>调用方必须处于事务中；方法返回前记录已经被更新为 PUBLISHING，
     * 因此其他扫描实例不会重复认领。</p>
     */
    public List<EventOutboxEntity> claimBatch(String workerId, Instant now, int batchSize) {
        if (batchSize <= 0) {
            return List.of();
        }
        String selectSql = "SELECT " + COLUMNS + " FROM event_outbox "
                + "WHERE status = 'NEW' AND available_at <= ? "
                + "AND (next_retry_at IS NULL OR next_retry_at <= ?) "
                + "ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED";
        List<EventOutboxEntity> candidates = jdbcTemplate.query(
                selectSql, ROW_MAPPER, Timestamp.from(now), Timestamp.from(now), batchSize);
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<Long> ids = candidates.stream().map(EventOutboxEntity::id).toList();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        String updateSql = "UPDATE event_outbox SET status='PUBLISHING',locked_by=?, "
                + "locked_at = ?, update_time = ?, last_error = NULL "
                + "WHERE status = 'NEW' AND id IN (" + placeholders + ")";
        List<Object> args = new ArrayList<>(3 + ids.size());
        args.add(workerId);
        args.add(Timestamp.from(now));
        args.add(Timestamp.from(now));
        args.addAll(ids);
        jdbcTemplate.update(updateSql, args.toArray());

        List<EventOutboxEntity> claimed = new ArrayList<>(candidates.size());
        for (EventOutboxEntity candidate : candidates) {
            claimed.add(new EventOutboxEntity(
                    candidate.id(), candidate.eventId(), candidate.aggregateType(),
                    candidate.aggregateId(), candidate.eventType(), candidate.routingKey(),
                    candidate.bizKey(), candidate.payload(), OutboxStatus.PUBLISHING,
                    candidate.retryCount(), candidate.nextRetryAt(), workerId, now,
                    candidate.lastError(), candidate.availableAt(), candidate.createTime(),
                    now, candidate.sentAt()));
        }
        return claimed;
    }

    /**
     * 批量标记 Broker 已确认的消息。
     */
    public int markSent(List<Long> ids, String workerId, Instant sentAt) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        String sql = "UPDATE event_outbox SET status = ?, sent_at = ?, locked_by = NULL, "
                + "locked_at = NULL, update_time = sent_at "
                + "WHERE status='PUBLISHING' AND locked_by=? AND id IN (" + placeholders + ")";
        List<Object> args = new ArrayList<>(3 + ids.size());
        args.add(OutboxStatus.SENT);
        args.add(Timestamp.from(sentAt));
        args.add(workerId);
        args.addAll(ids);
        return jdbcTemplate.update(sql, args.toArray());
    }

    /**
     * 发布失败后恢复为 NEW，并记录下一次可重试时间。
     */
    public int markRetry(long id, String workerId, int retryCount,
                         Instant nextRetryAt, String lastError, Instant now) {
        String sql = "UPDATE event_outbox SET status=?,retry_count=?,next_retry_at=?, "
                + "last_error = ?, update_time = ?, locked_by = NULL, locked_at = NULL "
                + "WHERE id=? AND status='PUBLISHING' AND locked_by=?";
        return jdbcTemplate.update(sql,
                OutboxStatus.NEW,
                retryCount,
                toTimestamp(nextRetryAt),
                lastError,
                Timestamp.from(now),
                id,
                workerId);
    }

    /**
     * 超过最大重试次数后标记为 FAILED，等待人工处理。
     */
    public int markFailed(long id, String workerId, String lastError, Instant now) {
        String sql = "UPDATE event_outbox SET status = ?, last_error = ?, update_time = ?, "
                + "locked_by = NULL, locked_at = NULL "
                + "WHERE id=? AND status='PUBLISHING' AND locked_by=?";
        return jdbcTemplate.update(sql,
                OutboxStatus.FAILED,
                lastError,
                Timestamp.from(now),
                id,
                workerId);
    }

    private static EventOutboxEntity mapRow(ResultSet rs) throws SQLException {
        return new EventOutboxEntity(
                rs.getLong("id"),
                rs.getString("event_id"),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                rs.getString("event_type"),
                rs.getString("routing_key"),
                rs.getString("biz_key"),
                rs.getString("payload"),
                rs.getString("status"),
                rs.getInt("retry_count"),
                instant(rs, "next_retry_at"),
                rs.getString("locked_by"),
                instant(rs, "locked_at"),
                rs.getString("last_error"),
                instant(rs, "available_at"),
                instant(rs, "create_time"),
                instant(rs, "update_time"),
                instant(rs, "sent_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
