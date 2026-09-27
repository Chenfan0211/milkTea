package com.wuling.common.outbox;

import java.time.Instant;

/**
 * 共享库中的事务内事件。
 *
 * <p>实体只承载数据库快照，不包含任何 RabbitMQ 客户端类型，
 * 便于业务事务和测试直接使用。</p>
 */
public record EventOutboxEntity(
        Long id,
        String eventId,
        String aggregateType,
        String aggregateId,
        String eventType,
        String routingKey,
        String bizKey,
        String payload,
        String status,
        int retryCount,
        Instant nextRetryAt,
        String lockedBy,
        Instant lockedAt,
        String lastError,
        Instant availableAt,
        Instant createTime,
        Instant updateTime,
        Instant sentAt
) {

    public static EventOutboxEntity newEvent(String eventId,
                                             String aggregateType,
                                             String aggregateId,
                                             String eventType,
                                             String routingKey,
                                             String bizKey,
                                             String payload,
                                             Instant now) {
        return newEvent(eventId, aggregateType, aggregateId, eventType, routingKey,
                bizKey, payload, now, now);
    }

    public static EventOutboxEntity newEvent(String eventId,
                                             String aggregateType,
                                             String aggregateId,
                                             String eventType,
                                             String routingKey,
                                             String bizKey,
                                             String payload,
                                             Instant availableAt,
                                             Instant now) {
        return new EventOutboxEntity(
                null, eventId, aggregateType, aggregateId, eventType, routingKey,
                bizKey, payload, OutboxStatus.NEW, 0, null, null, null,
                null, availableAt, now, now, null);
    }
}