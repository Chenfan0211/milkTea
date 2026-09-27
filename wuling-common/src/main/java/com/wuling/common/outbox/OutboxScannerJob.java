package com.wuling.common.outbox;

import com.wuling.common.mq.MqProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/** 定期认领并发布 event_outbox 事件。 */
@Component
@ConditionalOnClass({JdbcTemplate.class, org.springframework.amqp.rabbit.core.RabbitTemplate.class})
@ConditionalOnProperty(prefix = "app.outbox", name = "enabled", havingValue = "true")
public class OutboxScannerJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxScannerJob.class);

    private final OutboxService service;
    private final MqProducer producer;
    private final OutboxProperties properties;
    private final Executor executor;
    private final Clock clock;
    private final String workerId;

    public OutboxScannerJob(OutboxService service,
                            MqProducer producer,
                            OutboxProperties properties,
                            Executor executor,
                            Clock clock,
                            String workerId) {
        this.service = service;
        this.producer = producer;
        this.properties = properties;
        this.executor = executor == null ? ForkJoinPool.commonPool() : executor;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.workerId = workerId == null || workerId.isBlank() ? "outbox-worker" : workerId;
    }

    @Autowired
    public OutboxScannerJob(OutboxService service,
                            MqProducer producer,
                            OutboxProperties properties,
                            @Qualifier("applicationTaskExecutor") Executor executor,
                            @Value("${spring.application.name:unknown}") String applicationName,
                            @Value("${app.outbox.worker-id:}") String configuredWorkerId) {
        this(service, producer, properties, executor,
                Clock.systemUTC(), workerId(configuredWorkerId, applicationName));
    }

    @Scheduled(fixedDelayString = "${app.outbox.fixed-delay-ms:1000}")
    public void scan() {
        scanOnce();
    }

    /** 单次扫描，便于测试和运维手动触发。 */
    public void scanOnce() {
        Instant now = Instant.now(clock);
        List<EventOutboxEntity> events = service.claimBatch(
                workerId, now, properties.getBatchSize(), properties.getLockTimeoutMs());
        if (events == null || events.isEmpty()) {
            return;
        }

        List<Long> sentIds = new ArrayList<>();
        List<CompletableFuture<Void>> tasks = new ArrayList<>(events.size());
        for (EventOutboxEntity event : events) {
            CompletableFuture<Void> task = new CompletableFuture<>();
            tasks.add(task);
            try {
                executor.execute(() -> {
                    try {
                        publish(event);
                        synchronized (sentIds) {
                            sentIds.add(event.id());
                        }
                    } catch (Exception e) {
                        handlePublishFailure(event, e);
                    } finally {
                        task.complete(null);
                    }
                });
            } catch (RuntimeException e) {
                handlePublishFailure(event, e);
                task.complete(null);
            }
        }
        CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
        if (!sentIds.isEmpty()) {
            int updated = service.markSent(sentIds, workerId);
            if (updated != sentIds.size()) {
                log.warn("outbox 已确认事件状态更新不完整 claimed={} updated={} worker={}",
                        sentIds.size(), updated, workerId);
            }
        }
    }

    private void publish(EventOutboxEntity event) {
        producer.sendRawBodyWithId(
                event.routingKey(),
                event.payload().getBytes(StandardCharsets.UTF_8),
                event.eventId(),
                event.bizKey());
    }

    private void handlePublishFailure(EventOutboxEntity event, Exception error) {
        String message = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        int retryCount = event.retryCount();
        if (retryCount + 1 >= properties.getMaxRetries()) {
            service.markFailed(event, workerId, message);
            log.error("outbox 超过最大重试次数 eventId={} retryCount={} worker={} err={}",
                    event.eventId(), retryCount, workerId, message);
            return;
        }

        int nextRetryCount = retryCount + 1;
        long delayMs = retryDelayMs(retryCount);
        Instant nextRetryAt = Instant.now(clock).plusMillis(delayMs);
        service.markRetry(event, workerId, nextRetryCount, nextRetryAt, message);
        log.warn("outbox 发布失败 eventId={} retry={}/{} nextRetryAt={} worker={} err={}",
                event.eventId(), nextRetryCount, properties.getMaxRetries(),
                nextRetryAt, workerId, message);
    }

    private long retryDelayMs(int retryCount) {
        long initial = Math.max(1L, properties.getInitialRetryDelayMs());
        long cap = Math.max(initial, properties.getMaxRetryDelayMs());
        int shift = Math.min(Math.max(retryCount, 0), 30);
        long delay = initial;
        for (int i = 0; i < shift && delay < cap; i++) {
            if (delay > cap / 2) {
                return cap;
            }
            delay *= 2;
        }
        return Math.min(delay, cap);
    }

    private static String workerId(String configuredWorkerId, String applicationName) {
        if (configuredWorkerId != null && !configuredWorkerId.isBlank()) {
            return configuredWorkerId;
        }
        String app = applicationName == null || applicationName.isBlank() ? "unknown" : applicationName;
        return app + "-" + ProcessHandle.current().pid();
    }
}