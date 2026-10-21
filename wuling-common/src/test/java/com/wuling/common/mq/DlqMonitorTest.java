package com.wuling.common.mq;

import com.wuling.common.alert.AlertChannel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DlqMonitor 行为测试（第 10 期）。
 *
 * <p>用桩 AmqpAdmin + 记录型 AlertChannel 验证：
 * <ul>
 *   <li>队列有堆积 -> 发 CRITICAL 告警；</li>
 *   <li>队列不存在 -> 发 WARNING（这条能捕获「漏声明 DLQ」的 bug）；</li>
 *   <li>去重：连续两轮堆积只告警一次；</li>
 *   <li>恢复：从堆积回到 0 时发 INFO。</li>
 * </ul>
 */
class DlqMonitorTest {

    /** 记录所有告警，便于断言 */
    static class RecordingChannel implements AlertChannel {
        final List<String> alerts = new ArrayList<>();

        @Override
        public void send(Level level, String title, String detail, String bizKey) {
            alerts.add(level + "|" + title + "|" + bizKey);
        }

        @Override
        public String channelName() {
            return "recording";
        }
    }

    /** 可编程的 AmqpAdmin 桩 */
    static class StubAdmin implements AmqpAdmin {
        private final java.util.Map<String, Long> counts = new java.util.HashMap<>();
        private final java.util.Set<String> missing = new java.util.HashSet<>();

        void setCount(String q, long n) { counts.put(q, n); missing.remove(q); }
        void setMissing(String q) { counts.remove(q); missing.add(q); }

        @Override
        public QueueInformation getQueueInfo(String queueName) {
            if (missing.contains(queueName) || !counts.containsKey(queueName)) {
                return null;
            }
            return new QueueInformation(queueName, (int) (long) counts.get(queueName), 0);
        }

        // ---------- 其余接口本测试用不到，空实现 ----------
        @Override public void declareExchange(org.springframework.amqp.core.Exchange exchange) { }
        @Override public boolean deleteExchange(String exchangeName) { return false; }
        @Override public org.springframework.amqp.core.Queue declareQueue() { return new org.springframework.amqp.core.Queue("stub"); }
        @Override public String declareQueue(org.springframework.amqp.core.Queue queue) { return queue.getName(); }
        @Override public boolean deleteQueue(String queueName) { return false; }
        @Override public void deleteQueue(String queueName, boolean unused, boolean empty) { }
        @Override public void purgeQueue(String queueName, boolean noWait) { }
        @Override public int purgeQueue(String queueName) { return 0; }
        @Override public void declareBinding(org.springframework.amqp.core.Binding binding) { }
        @Override public void removeBinding(org.springframework.amqp.core.Binding binding) { }
        @Override public java.util.Properties getQueueProperties(String queueName) { return null; }
    }

    /**
     * 把所有被监控的 DLQ 置为 0。
     *
     * <p>刻意从 {@link DlqMonitor#MONITORED} 取清单，而不是在测试里再抄一份：
     * 抄一份的代价就是「新增 DLQ 却忘了同步桩」——桩里没有的队列会被 getQueueInfo 返回 null，
     * 于是被判成「队列不存在」发 WARNING，用例集体变红（2026-10 已实际发生）。
     */
    private static void stubAllZero(StubAdmin admin) {
        for (String q : DlqMonitor.MONITORED) {
            admin.setCount(q, 0);
        }
    }

    @Test
    void monitoredListShouldCoverNewlyAddedDlqs() {
        assertTrue(DlqMonitor.MONITORED.contains(MqConstants.COUPON_EVENT_DLQ),
                "优惠券事件 DLQ 必须被监控");
        assertTrue(DlqMonitor.MONITORED.contains(MqConstants.DEVICE_MESSAGE_DLQ),
                "设备消息 DLQ 必须被监控，否则消息进死信后无人察觉");
        assertTrue(DlqMonitor.MONITORED.size() >= 8,
                "被监控 DLQ 数量应随 MqConstants 增长（新增 DLQ 时同步补进 MONITORED）");
    }

    private DlqMonitor monitor(StubAdmin admin, RecordingChannel ch) {
        return new DlqMonitor(admin, ch, true, 1);
    }

    @Test
    void shouldAlertWhenDlqHasMessages() {
        StubAdmin admin = new StubAdmin();
        RecordingChannel ch = new RecordingChannel();
        // 全部置 0，只有 split 队列有 3 条
        stubAllZero(admin);
        admin.setCount(MqConstants.FINANCE_SPLIT_DLQ, 3);

        monitor(admin, ch).checkDlq();

        assertEquals(1, ch.alerts.size(), "应产生 1 条告警");
        assertTrue(ch.alerts.get(0).contains("CRITICAL"), "应为 CRITICAL 级别");
        assertTrue(ch.alerts.get(0).contains(MqConstants.FINANCE_SPLIT_DLQ), "应指明队列名");
    }

    @Test
    void shouldWarnWhenDlqNotDeclared() {
        StubAdmin admin = new StubAdmin();
        RecordingChannel ch = new RecordingChannel();
        // 全部正常，只有 split 未声明（模拟漏声明 DLQ）
        stubAllZero(admin);
        admin.setMissing(MqConstants.FINANCE_SPLIT_DLQ);

        monitor(admin, ch).checkDlq();

        assertEquals(1, ch.alerts.size(), "应产生 1 条告警");
        assertTrue(ch.alerts.get(0).contains("WARNING"), "队列不存在应为 WARNING");
        assertTrue(ch.alerts.get(0).contains("死信队列不存在"), "标题应说明问题");
    }

    @Test
    void shouldNotRepeatAlertOnConsecutiveRuns() {
        StubAdmin admin = new StubAdmin();
        RecordingChannel ch = new RecordingChannel();
        stubAllZero(admin);
        admin.setCount(MqConstants.FINANCE_SPLIT_DLQ, 5);

        DlqMonitor m = monitor(admin, ch);
        m.checkDlq();
        m.checkDlq();
        m.checkDlq();

        assertEquals(1, ch.alerts.size(), "连续多轮堆积只应告警一次（去重）");
    }

    @Test
    void shouldNotifyRecovery() {
        StubAdmin admin = new StubAdmin();
        RecordingChannel ch = new RecordingChannel();
        stubAllZero(admin);
        admin.setCount(MqConstants.FINANCE_SPLIT_DLQ, 2);

        DlqMonitor m = monitor(admin, ch);
        m.checkDlq();                    // 告警
        admin.setCount(MqConstants.FINANCE_SPLIT_DLQ, 0);
        m.checkDlq();                    // 恢复

        assertEquals(2, ch.alerts.size());
        assertTrue(ch.alerts.get(1).contains("INFO"), "恢复应发 INFO");
        assertTrue(ch.alerts.get(1).contains("已清空"), "标题应为已清空");
    }
}
