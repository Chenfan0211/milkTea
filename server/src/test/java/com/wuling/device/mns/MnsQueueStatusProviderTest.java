package com.wuling.device.mns;

import com.aliyun.mns.model.QueueMeta;
import com.wuling.device.config.MnsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MNS 队列自检测试（不联网：队列属性查询走注入的假实现）。
 *
 * <p>这段逻辑的价值全在「把没有数据的原因说清楚」，因此测试锁定四件事：
 * <ol>
 *   <li>队列属性正确映射，且快照里不出现任何密钥；</li>
 *   <li>成功结果走 10 分钟缓存、{@code refresh=1} 强制实时查询；</li>
 *   <li>查询失败返回 {@code ok=false} + 原因而不是抛异常（自检不能变成新的故障点），
 *       且失败只缓存 1 分钟，一次网络抖动不会被放大成十分钟不可用；</li>
 *   <li>消费端心跳/计数口径：轮询异常不算「转投失败」，成功转投会清掉历史错误。</li>
 * </ol>
 */
class MnsQueueStatusProviderTest {

    private static final String AK = "ak-must-not-leak";
    private static final String SK = "sk-must-not-leak";

    private MnsProperties properties;
    private AtomicLong clock;
    private AtomicInteger inspectCalls;
    private MnsQueueStatusProvider provider;

    @BeforeEach
    void setUp() {
        properties = new MnsProperties();
        properties.setEnabled(true);
        properties.setEndpoint("http://1862421895270680.mns.cn-shanghai.aliyuncs.com");
        properties.setQueueName("aliyun-hub-WLSG2026");
        properties.setAccessKeyId(AK);
        properties.setAccessKeySecret(SK);
        clock = new AtomicLong(1_700_000_000_000L);
        inspectCalls = new AtomicInteger();
        provider = new MnsQueueStatusProvider(properties, props -> {
            inspectCalls.incrementAndGet();
            QueueMeta meta = new QueueMeta();
            meta.setActiveMessages(3L);
            meta.setInactiveMessages(1L);
            meta.setDelayMessages(0L);
            meta.setCreateTime(new Date(1_600_000_000_000L));
            meta.setLastModifyTime(new Date(1_600_000_100_000L));
            return meta;
        }, clock::get);
    }

    @Test
    void springConstructorIsExplicitlyAnnotated() {
        // 守卫：本类有多个构造器，若生产构造器没有 @Autowired，Spring 会退回默认构造器策略，
        // 启动期抛 No default constructor found —— 2026-10-16 上线时正是这样把 server 打挂的。
        long annotated = Arrays.stream(MnsQueueStatusProvider.class.getDeclaredConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .count();
        assertEquals(1, annotated, "多构造器 Bean 必须显式标注 @Autowired");

        Constructor<?> springConstructor = Arrays.stream(MnsQueueStatusProvider.class.getDeclaredConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .findFirst()
                .orElseThrow();
        assertEquals(1, springConstructor.getParameterCount(), "Spring 只应注入 MnsProperties");
    }

    @Test
    void attributesAreMappedWithoutSecrets() {
        Map<String, Object> snapshot = provider.snapshot(false);

        assertEquals(true, snapshot.get("ok"));
        assertEquals(false, snapshot.get("cached"));
        assertEquals("aliyun-hub-WLSG2026", snapshot.get("queueName"));
        assertEquals(3L, snapshot.get("activeMessages"));
        assertEquals(1L, snapshot.get("inactiveMessages"));
        assertEquals(0L, snapshot.get("delayMessages"));
        assertNotNull(snapshot.get("lastCheckTime"));
        assertNotNull(snapshot.get("queueCreateTime"));
        assertEquals(1, inspectCalls.get());
        // 快照会被后台页面直接展示，绝不能带上 AccessKey / Secret
        assertTrue(snapshot.values().stream()
                        .filter(value -> value instanceof String)
                        .map(String.class::cast)
                        .noneMatch(value -> value.contains(AK) || value.contains(SK)),
                "队列快照不得出现密钥");
    }

    @Test
    void successIsCachedUntilTtl() {
        provider.snapshot(false);
        Map<String, Object> cached = provider.snapshot(false);

        assertEquals(true, cached.get("cached"));
        assertEquals(1, inspectCalls.get(), "10 分钟内不应重复查询队列属性");

        clock.addAndGet(MnsQueueStatusProvider.SUCCESS_CACHE_MILLIS + 1);
        provider.snapshot(false);
        assertEquals(2, inspectCalls.get(), "超过缓存时长后应重新查询");
    }

    @Test
    void refreshForcesInspection() {
        provider.snapshot(false);
        Map<String, Object> refreshed = provider.snapshot(true);

        assertEquals(false, refreshed.get("cached"));
        assertEquals(2, inspectCalls.get());
    }

    @Test
    void inspectionFailureIsReportedNotThrown() {
        MnsQueueStatusProvider failing = new MnsQueueStatusProvider(properties, props -> {
            throw new IllegalStateException("QueueNotExist");
        }, clock::get);

        Map<String, Object> snapshot = failing.snapshot(false);

        assertEquals(false, snapshot.get("ok"));
        assertEquals(false, snapshot.get("cached"));
        assertTrue(String.valueOf(snapshot.get("error")).contains("QueueNotExist"),
                "失败原因必须回传，便于判断是权限/队列名还是网络问题");
        assertNull(snapshot.get("activeMessages"));
    }

    @Test
    void failureIsCachedOnlyBriefly() {
        AtomicInteger failingCalls = new AtomicInteger();
        MnsQueueStatusProvider failing = new MnsQueueStatusProvider(properties, props -> {
            failingCalls.incrementAndGet();
            throw new IllegalStateException("timeout");
        }, clock::get);

        failing.snapshot(false);
        Map<String, Object> stillCached = failing.snapshot(false);
        assertEquals(true, stillCached.get("cached"), "失败结果在 1 分钟短窗口内可复用，避免每次刷新都打公网");
        assertEquals(1, failingCalls.get());

        clock.addAndGet(MnsQueueStatusProvider.FAILURE_CACHE_MILLIS + 1);
        Map<String, Object> retried = failing.snapshot(false);
        assertEquals(2, failingCalls.get(), "超过短缓存窗口必须重试，不能把一次抖动缓存成十分钟不可用");
        assertEquals(false, retried.get("cached"));
        assertTrue(String.valueOf(retried.get("error")).contains("timeout"));
    }

    @Test
    void disabledConsumerExplainsItselfWithoutQuerying() {
        properties.setEnabled(false);
        MnsQueueStatusProvider disabled = new MnsQueueStatusProvider(properties, props -> {
            throw new AssertionError("未启用时不应查询队列属性");
        }, clock::get);

        Map<String, Object> snapshot = disabled.snapshot(false);

        assertEquals(false, snapshot.get("enabled"));
        assertEquals(false, snapshot.get("ok"));
        assertTrue(String.valueOf(snapshot.get("error")).contains("未启用"));
    }

    @Test
    void consumerHeartbeatAndCountersAreExposed() {
        provider.markRunning(true);
        provider.markConnected(true);
        provider.markPolled();
        provider.markMessageReceived();
        provider.markForwarded(true, null);
        provider.markForwarded(false, "投递失败");

        Map<String, Object> snapshot = provider.snapshot(false);

        assertEquals(true, snapshot.get("running"));
        assertEquals(true, snapshot.get("connected"));
        assertNotNull(snapshot.get("lastPollTime"));
        assertNotNull(snapshot.get("lastMessageTime"));
        assertEquals(1L, snapshot.get("forwardedCount"));
        assertEquals(1L, snapshot.get("failedCount"));
        assertEquals("投递失败", snapshot.get("lastError"));

        // 恢复正常后不应继续显示历史错误（否则会被误读成「现在还在坏」）
        provider.markForwarded(true, null);
        assertNull(provider.snapshot(false).get("lastError"));
    }

    @Test
    void pollErrorIsNotCountedAsForwardFailure() {
        provider.markPollError("Connection reset");

        Map<String, Object> snapshot = provider.snapshot(false);

        assertEquals(0L, snapshot.get("failedCount"));
        assertEquals("Connection reset", snapshot.get("lastError"));
        assertEquals(true, snapshot.get("ok"), "轮询异常只记错误摘要，不影响队列属性本身是否查询成功");
    }
}
