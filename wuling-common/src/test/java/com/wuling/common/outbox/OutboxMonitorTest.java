package com.wuling.common.outbox;

import com.wuling.common.alert.AlertChannel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OutboxMonitor 行为测试。
 *
 * <p>用 mock EventOutboxMapper + 记录型 AlertChannel 验证：
 * <ul>
 *   <li>FAILED > 0 -> CRITICAL 告警；</li>
 *   <li>NEW 滞留 -> CRITICAL 告警；</li>
 *   <li>PUBLISHING 卡死 -> WARNING 告警；</li>
 *   <li>去重：连续多轮只告警一次；恢复发 INFO。</li>
 * </ul>
 */
class OutboxMonitorTest {

    static class RecordingChannel implements AlertChannel {
        final List<String> alerts = new ArrayList<>();
        @Override public void send(Level level, String title, String detail, String bizKey) {
            alerts.add(level + "|" + title + "|" + bizKey);
        }
        @Override public String channelName() { return "recording"; }
    }

    private OutboxMonitor monitor(EventOutboxMapper mapper, RecordingChannel ch) {
        return new OutboxMonitor(mapper, ch, fixedClock(), true, 300000, 300000);
    }

    private EventOutboxMapper mapperReturning(EventOutboxMapper.OutboxHealth health) {
        EventOutboxMapper mapper = mock(EventOutboxMapper.class);
        when(mapper.health(any(Instant.class), anyLong(), anyLong())).thenReturn(health);
        return mapper;
    }

    @Test
    void shouldAlertOnFailedEvents() {
        EventOutboxMapper mapper = mapperReturning(new EventOutboxMapper.OutboxHealth(3, 0, 0));
        RecordingChannel ch = new RecordingChannel();
        monitor(mapper, ch).checkOutbox();
        assertEquals(1, ch.alerts.size());
        assertTrue(ch.alerts.get(0).contains("CRITICAL"));
        assertTrue(ch.alerts.get(0).contains("FAILED"));
    }

    @Test
    void shouldAlertOnStaleNew() {
        EventOutboxMapper mapper = mapperReturning(new EventOutboxMapper.OutboxHealth(0, 5, 0));
        RecordingChannel ch = new RecordingChannel();
        monitor(mapper, ch).checkOutbox();
        assertEquals(1, ch.alerts.size());
        assertTrue(ch.alerts.get(0).contains("CRITICAL"));
        assertTrue(ch.alerts.get(0).contains("滞留"));
    }

    @Test
    void shouldWarnOnStalePublishing() {
        EventOutboxMapper mapper = mapperReturning(new EventOutboxMapper.OutboxHealth(0, 0, 2));
        RecordingChannel ch = new RecordingChannel();
        monitor(mapper, ch).checkOutbox();
        assertEquals(1, ch.alerts.size());
        assertTrue(ch.alerts.get(0).contains("WARNING"));
        assertTrue(ch.alerts.get(0).contains("卡死"));
    }

    @Test
    void shouldNotRepeatAlertOnConsecutiveRuns() {
        EventOutboxMapper mapper = mapperReturning(new EventOutboxMapper.OutboxHealth(1, 0, 0));
        RecordingChannel ch = new RecordingChannel();
        OutboxMonitor m = monitor(mapper, ch);
        m.checkOutbox();
        m.checkOutbox();
        m.checkOutbox();
        assertEquals(1, ch.alerts.size(), "连续多轮 FAILED 只应告警一次");
    }

    @Test
    void shouldNotifyRecovery() {
        EventOutboxMapper mapper = mock(EventOutboxMapper.class);
        when(mapper.health(any(Instant.class), anyLong(), anyLong()))
                .thenReturn(new EventOutboxMapper.OutboxHealth(1, 0, 0))
                .thenReturn(new EventOutboxMapper.OutboxHealth(0, 0, 0));
        RecordingChannel ch = new RecordingChannel();
        OutboxMonitor m = monitor(mapper, ch);
        m.checkOutbox(); // 告警
        m.checkOutbox(); // 恢复
        assertEquals(2, ch.alerts.size());
        assertTrue(ch.alerts.get(1).contains("INFO"), "恢复应发 INFO");
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-09-27T10:00:00Z"), ZoneOffset.UTC);
    }
}
