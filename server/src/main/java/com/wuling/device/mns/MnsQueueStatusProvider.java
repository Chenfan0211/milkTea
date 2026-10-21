package com.wuling.device.mns;

import com.aliyun.mns.client.CloudAccount;
import com.aliyun.mns.client.CloudQueue;
import com.aliyun.mns.client.MNSClient;
import com.aliyun.mns.common.http.ClientConfiguration;
import com.aliyun.mns.model.QueueMeta;
import com.wuling.device.config.MnsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * MNS 队列自检：回答「对方到底推没推」这一个问题。
 *
 * <p><b>为什么需要它</b>：对接对方推送式队列时，「页面没数据」可能有两类完全不同的原因——
 * 对方没在推，或我们没在收。二者在应用侧完全同形（都是零数据、零报错），
 * 只能靠队列自身的计数区分。本组件把队列属性（可见 / 不可见 / 延迟消息数）
 * 与消费端的实时状态拼成一份快照，供后台「设备管理」页与排障使用。
 *
 * <p><b>为什么缓存</b>：查询队列属性要走一次公网请求。后台列表页可能被频繁打开，
 * 因此成功结果缓存 {@value #SUCCESS_CACHE_MILLIS} ms（与自动自检周期一致），
 * 失败结果只缓存 {@value #FAILURE_CACHE_MILLIS} ms —— 一次性网络抖动不该被放大成
 * 「十分钟不可用」，但也别让每次刷新都去打一次公网。
 *
 * <p><b>安全</b>：快照只输出队列名、endpoint、消息计数、时间与错误摘要，
 * 绝不包含 AccessKey / Secret。
 */
@Component
public class MnsQueueStatusProvider {

    private static final Logger log = LoggerFactory.getLogger(MnsQueueStatusProvider.class);

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 成功结果缓存时长：10 分钟（与消费端自动自检周期一致） */
    static final long SUCCESS_CACHE_MILLIS = 10 * 60 * 1000L;
    /** 失败结果缓存时长：1 分钟（网络抖动不放大，但也不反复打公网） */
    static final long FAILURE_CACHE_MILLIS = 60 * 1000L;

    /** 自检客户端超时：连接 5s、读 10s，避免后台请求被公网卡住 */
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int SOCKET_TIMEOUT_MS = 10_000;

    /**
     * 队列属性查询密封点：真实实现走 MNS SDK，单测注入假实现即可覆盖全部分支
     * （不需要联网、也不需要动 SDK 静态代码）。
     */
    interface QueueInspector {
        QueueMeta inspect(MnsProperties properties) throws Exception;
    }

    private final MnsProperties properties;
    private final QueueInspector inspector;
    private final LongSupplier clock;

    /** 消费端运行状态（消费线程写、HTTP 线程读，故全部 volatile） */
    private volatile boolean running;
    private volatile boolean connected;
    private volatile long lastPollTime;
    private volatile long lastMessageTime;
    private volatile long forwardedCount;
    private volatile long failedCount;
    private volatile String lastError;

    private volatile Attributes attributes;

    /**
     * 生产用构造器。
     *
     * <p><b>必须显式标注 {@code @Autowired}</b>：本类还有一个包私有的测试构造器，
     * 一旦 Spring 看到多个构造器且没有任何一个被标注，就会退回「默认构造器」策略 ——
     * 结果是启动期抛 {@code No default constructor found}，整个服务起不来。
     * 2026-10-16 上线时踩过这个坑（已回滚），MnsQueueStatusProviderTest 里有守卫测试。
     */
    @Autowired
    public MnsQueueStatusProvider(MnsProperties properties) {
        this(properties, MnsQueueStatusProvider::inspectWithSdk, System::currentTimeMillis);
    }

    MnsQueueStatusProvider(MnsProperties properties, QueueInspector inspector, LongSupplier clock) {
        this.properties = properties;
        this.inspector = inspector;
        this.clock = clock;
    }

    // ---------- 消费端上报 ----------

    public void markRunning(boolean value) {
        this.running = value;
    }

    public void markConnected(boolean value) {
        this.connected = value;
    }

    /** 每次长轮询返回（无论是否有消息）都算一次心跳。 */
    public void markPolled() {
        this.lastPollTime = clock.getAsLong();
    }

    public void markMessageReceived() {
        this.lastMessageTime = clock.getAsLong();
    }

    /**
     * 记录一次转投结果。
     *
     * @param ok    RabbitMQ 发布确认是否成功
     * @param error 失败原因（成功时传 null，成功后清掉历史错误）
     */
    public void markForwarded(boolean ok, String error) {
        if (ok) {
            forwardedCount++;
            lastError = null;
            return;
        }
        failedCount++;
        lastError = error;
    }

    /** 报文解析失败（坏消息）：计入失败计数，但不算丢失。 */
    public void markParseFailed(String reason) {
        failedCount++;
        lastError = reason;
    }

    /**
     * 轮询本身异常（连不上 / 权限错 / 队列不存在）。
     *
     * <p>与「转投失败」分开计数：轮询异常往往是一段时间内持续发生的连接问题，
     * 混进转投失败计数会让「到底丢没丢消息」变得难以判断。
     */
    public void markPollError(String reason) {
        lastError = reason;
    }

    // ---------- 快照 ----------

    /**
     * 队列状态快照。
     *
     * @param refresh true=强制实时查询队列属性，false=命中缓存时直接用缓存
     */
    public Map<String, Object> snapshot(boolean refresh) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean enabled = properties.isEnabled();
        result.put("enabled", enabled);
        result.put("running", running);
        result.put("connected", connected);
        result.put("queueName", blankToNull(properties.getQueueName()));
        result.put("endpoint", blankToNull(properties.getEndpoint()));
        result.put("lastPollTime", stamp(lastPollTime));
        result.put("lastMessageTime", stamp(lastMessageTime));
        result.put("forwardedCount", forwardedCount);
        result.put("failedCount", failedCount);
        result.put("lastError", lastError);

        if (!enabled) {
            result.put("ok", false);
            result.put("cached", false);
            result.put("error", "MNS 消费端未启用（app.mns.enabled=false），当前不会有设备数据流入");
            return result;
        }

        Attributes current = current(refresh);
        result.put("ok", current.ok);
        result.put("cached", current.cached);
        result.put("error", current.error);
        result.put("lastCheckTime", stamp(current.checkTime));
        result.put("activeMessages", current.activeMessages);
        result.put("inactiveMessages", current.inactiveMessages);
        result.put("delayMessages", current.delayMessages);
        result.put("queueCreateTime", stamp(current.queueCreateTime));
        result.put("queueLastModifyTime", stamp(current.queueLastModifyTime));
        return result;
    }

    /** 立即自检并打日志（消费端每 10 分钟调用一次，无需打开后台即可判断对方是否在推）。 */
    public Map<String, Object> selfCheck() {
        Map<String, Object> snapshot = snapshot(true);
        if (Boolean.TRUE.equals(snapshot.get("ok"))) {
            log.info("MNS 队列自检 queue={} 可见消息={} 不可见消息={} 最近收到={}",
                    snapshot.get("queueName"), snapshot.get("activeMessages"),
                    snapshot.get("inactiveMessages"), describe(snapshot.get("lastMessageTime")));
        } else {
            log.warn("MNS 队列自检失败 queue={} reason={}",
                    snapshot.get("queueName"), describe(snapshot.get("error")));
        }
        return snapshot;
    }

    // ---------- 内部 ----------

    private Attributes current(boolean refresh) {
        Attributes cached = attributes;
        long now = clock.getAsLong();
        if (!refresh && cached != null && now - cached.checkTime < cached.ttlMillis()) {
            return cached.asCached();
        }
        return inspect(now);
    }

    private Attributes inspect(long now) {
        Attributes result;
        try {
            result = Attributes.of(inspector.inspect(properties), now);
        } catch (Exception e) {
            String reason = e.getClass().getSimpleName() + ": " + describe(e.getMessage());
            log.warn("MNS 队列属性查询失败 queue={} reason={}", properties.getQueueName(), reason);
            result = Attributes.failed(reason, now);
        }
        attributes = result;
        return result;
    }

    /** 默认实现：每次查询新建短连接客户端并在用完即关，避免与消费端长连接互相影响。 */
    private static QueueMeta inspectWithSdk(MnsProperties properties) throws Exception {
        ClientConfiguration configuration = new ClientConfiguration();
        configuration.setConnectionTimeout(CONNECT_TIMEOUT_MS);
        configuration.setSocketTimeout(SOCKET_TIMEOUT_MS);
        MNSClient client = null;
        try {
            client = new CloudAccount(properties.getAccessKeyId(), properties.getAccessKeySecret(),
                    properties.getEndpoint(), configuration).getMNSClient();
            CloudQueue queue = client.getQueueRef(properties.getQueueName());
            return queue.getAttributes();
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    private static String stamp(Long millis) {
        if (millis == null || millis <= 0L) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZONE).format(FMT);
    }

    private static String stamp(Date date) {
        return date == null ? null : LocalDateTime.ofInstant(date.toInstant(), ZONE).format(FMT);
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private static String describe(Object value) {
        return value == null || String.valueOf(value).isBlank() ? "无" : String.valueOf(value);
    }

    /** 一次队列属性查询的结果（不可变；asCached 只改「是否来自缓存」这一展示字段）。 */
    private static final class Attributes {

        private final boolean ok;
        private final String error;
        private final Long activeMessages;
        private final Long inactiveMessages;
        private final Long delayMessages;
        private final Long queueCreateTime;
        private final Long queueLastModifyTime;
        private final long checkTime;
        private final boolean cached;

        private Attributes(boolean ok, String error, Long activeMessages, Long inactiveMessages, Long delayMessages,
                           Long queueCreateTime, Long queueLastModifyTime, long checkTime, boolean cached) {
            this.ok = ok;
            this.error = error;
            this.activeMessages = activeMessages;
            this.inactiveMessages = inactiveMessages;
            this.delayMessages = delayMessages;
            this.queueCreateTime = queueCreateTime;
            this.queueLastModifyTime = queueLastModifyTime;
            this.checkTime = checkTime;
            this.cached = cached;
        }

        static Attributes of(QueueMeta meta, long checkTime) {
            if (meta == null) {
                return failed("队列属性返回为空", checkTime);
            }
            return new Attributes(true, null, meta.getActiveMessages(), meta.getInactiveMessages(),
                    meta.getDelayMessages(), millis(meta.getCreateTime()), millis(meta.getLastModifyTime()),
                    checkTime, false);
        }

        static Attributes failed(String error, long checkTime) {
            return new Attributes(false, error, null, null, null, null, null, checkTime, false);
        }

        Attributes asCached() {
            return new Attributes(ok, error, activeMessages, inactiveMessages, delayMessages, queueCreateTime,
                    queueLastModifyTime, checkTime, true);
        }

        long ttlMillis() {
            return ok ? SUCCESS_CACHE_MILLIS : FAILURE_CACHE_MILLIS;
        }

        private static Long millis(Date date) {
            return date == null ? null : date.getTime();
        }
    }
}
