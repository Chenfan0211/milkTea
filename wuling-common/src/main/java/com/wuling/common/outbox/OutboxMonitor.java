package com.wuling.common.outbox;

import com.wuling.common.alert.AlertChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * event_outbox 健康监控。
 *
 * <p>outbox 是支付成功奖励、优惠券状态、超时关单、分账、冲正等最终一致事件
 * 的唯一可靠投递通道。若扫描器静默故障（MQ 断连、进程卡死），事件会一直停留在
 * event_outbox 表，最终导致「订单已付款但奖励未发 / 券未核销 / 分账未执行」。
 *
 * <p>这里监控三类异常：
 * <ol>
 *   <li>{@code FAILED} —— 超过最大重试次数，需人工核查失败原因并补偿；</li>
 *   <li>{@code NEW} 且 available_at 早已过期 —— 扫描器没在投递，疑似扫描器故障；</li>
 *   <li>{@code PUBLISHING} 且 locked_at 早已过期 —— 发布进程卡死，事件被锁住无法重试。</li>
 * </ol>
 *
 * <p>告警去重：连续多轮超阈值只在首次告警，恢复时发 INFO 便于确认。
 */
@Component
@ConditionalOnClass(JdbcTemplate.class)
public class OutboxMonitor {

    private static final Logger log = LoggerFactory.getLogger(OutboxMonitor.class);

    private final EventOutboxMapper mapper;
    private final AlertChannel alertChannel;
    private final Clock clock;
    private final boolean enabled;

    /** NEW 事件 available_at 过期超过该时长仍未发布则告警（毫秒） */
    private final long staleNewMs;
    /** PUBLISHING 事件 locked_at 过期超过该时长仍未完成则告警（毫秒） */
    private final long stalePublishingMs;

    private boolean failedAlerting;
    private boolean staleNewAlerting;
    private boolean stalePublishingAlerting;

    @Autowired
    public OutboxMonitor(EventOutboxMapper mapper,
                         AlertChannel alertChannel,
                         @Value("${app.outbox.monitor.enabled:true}") boolean enabled,
                         @Value("${app.outbox.monitor.stale-new-ms:300000}") long staleNewMs,
                         @Value("${app.outbox.monitor.stale-publishing-ms:300000}") long stalePublishingMs) {
        this(mapper, alertChannel, Clock.systemUTC(), enabled, staleNewMs, stalePublishingMs);
    }

    OutboxMonitor(EventOutboxMapper mapper,
                  AlertChannel alertChannel,
                  Clock clock,
                  boolean enabled,
                  long staleNewMs,
                  long stalePublishingMs) {
        this.mapper = mapper;
        this.alertChannel = alertChannel;
        this.clock = clock;
        this.enabled = enabled;
        this.staleNewMs = staleNewMs;
        this.stalePublishingMs = stalePublishingMs;
    }

    /** 每 5 分钟检查一次（与 DLQ 监控同频，避免过于频繁刷告警）。 */
    @Scheduled(cron = "${app.outbox.monitor.cron:0 */5 * * * ?}")
    public void checkOutbox() {
        if (!enabled) {
            return;
        }
        try {
            Instant now = Instant.now(clock);
            EventOutboxMapper.OutboxHealth health = mapper.health(now, staleNewMs, stalePublishingMs);

            if (health.failedCount() > 0) {
                if (!failedAlerting) {
                    alertChannel.send(AlertChannel.Level.CRITICAL, "outbox 存在 FAILED 事件",
                            "event_outbox 有 " + health.failedCount() + " 条超过重试上限的失败事件，需人工补偿",
                            "event_outbox:FAILED");
                }
                failedAlerting = true;
            } else if (failedAlerting) {
                alertChannel.send(AlertChannel.Level.INFO, "outbox FAILED 已清空", "event_outbox 失败事件已处理", "event_outbox:FAILED");
                failedAlerting = false;
            }

            if (health.staleNewCount() > 0) {
                if (!staleNewAlerting) {
                    alertChannel.send(AlertChannel.Level.CRITICAL, "outbox 事件滞留未发布",
                            "event_outbox 有 " + health.staleNewCount() + " 条 NEW 事件超过 "
                                    + staleNewMs + "ms 未发布，扫描器可能故障",
                            "event_outbox:STALE_NEW");
                }
                staleNewAlerting = true;
            } else if (staleNewAlerting) {
                alertChannel.send(AlertChannel.Level.INFO, "outbox 滞留事件已恢复", "event_outbox 滞留 NEW 事件已处理", "event_outbox:STALE_NEW");
                staleNewAlerting = false;
            }

            if (health.stalePublishingCount() > 0) {
                if (!stalePublishingAlerting) {
                    alertChannel.send(AlertChannel.Level.WARNING, "outbox 发布事件卡死",
                            "event_outbox 有 " + health.stalePublishingCount() + " 条 PUBLISHING 事件超过 "
                                    + stalePublishingMs + "ms 未完成，发布进程可能卡死",
                            "event_outbox:STALE_PUBLISHING");
                }
                stalePublishingAlerting = true;
            } else if (stalePublishingAlerting) {
                alertChannel.send(AlertChannel.Level.INFO, "outbox 卡死事件已恢复", "event_outbox 卡死 PUBLISHING 事件已处理", "event_outbox:STALE_PUBLISHING");
                stalePublishingAlerting = false;
            }
        } catch (Exception e) {
            // 监控失败不影响业务
            log.warn("outbox 监控执行失败: {}", e.getMessage());
        }
    }
}
