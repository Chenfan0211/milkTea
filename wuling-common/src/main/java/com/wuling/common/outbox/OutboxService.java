package com.wuling.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** event_outbox 的应用服务。 */
@Service
@ConditionalOnClass(JdbcTemplate.class)
public class OutboxService {

    private final EventOutboxMapper mapper;
    private final ObjectMapper objectMapper;
    private final OutboxProperties properties;
    private final Clock clock;

    @Autowired
    public OutboxService(EventOutboxMapper mapper,
                         ObjectMapper objectMapper,
                         OutboxProperties properties) {
        this(mapper, objectMapper, properties, Clock.systemUTC());
    }

    public OutboxService(EventOutboxMapper mapper, ObjectMapper objectMapper) {
        this(mapper, objectMapper, new OutboxProperties(), Clock.systemUTC());
    }

    public OutboxService(EventOutboxMapper mapper,
                         ObjectMapper objectMapper,
                         OutboxProperties properties,
                         Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 在调用方本地事务中登记事件。
     *
     * <p>这里故意使用 MANDATORY：如果调用方没有事务，直接失败，
     * 避免出现业务数据已提交而 outbox 事件单独提交的窗口。</p>
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String enqueue(String aggregateType,
                          String aggregateId,
                          String eventType,
                          String routingKey,
                          String bizKey,
                          Object payload) {
        String eventId = UUID.randomUUID().toString();
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("outbox payload serialization failed", e);
        }
        Instant now = Instant.now(clock);
        EventOutboxEntity event = EventOutboxEntity.newEvent(
                eventId, aggregateType, aggregateId, eventType, routingKey,
                bizKey, payloadJson, now);
        mapper.insert(event);
        return eventId;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<EventOutboxEntity> claimBatch(String workerId, Instant now) {
        return claimBatch(workerId, now, properties.getBatchSize());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<EventOutboxEntity> claimBatch(String workerId, Instant now, int batchSize) {
        return mapper.claimBatch(workerId, now, batchSize);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int markSent(List<Long> ids, String workerId) {
        return mapper.markSent(ids, workerId, Instant.now(clock));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int markRetry(EventOutboxEntity event,
                         String workerId,
                         int retryCount,
                         Instant nextRetryAt,
                         String lastError) {
        return mapper.markRetry(
                event.id(), workerId, retryCount, nextRetryAt, lastError, Instant.now(clock));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int markFailed(EventOutboxEntity event, String workerId, String lastError) {
        return mapper.markFailed(event.id(), workerId, lastError, Instant.now(clock));
    }
}