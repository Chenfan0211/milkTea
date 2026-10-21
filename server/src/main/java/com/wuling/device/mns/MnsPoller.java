package com.wuling.device.mns;

import com.aliyun.mns.client.CloudAccount;
import com.aliyun.mns.client.CloudQueue;
import com.aliyun.mns.client.MNSClient;
import com.aliyun.mns.model.Message;
import com.wuling.common.mq.MqConstants;
import com.wuling.common.mq.MqProducer;
import com.wuling.device.config.MnsProperties;
import com.wuling.device.service.DeviceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 阿里云 MNS（轻量消息队列）设备消息消费端。
 *
 * <p>运行形态：应用内常驻单线程长轮询（对方明确要求「消费端要一直开着，实时消费消息」），
 * 拉到消息后【规范化 → 转投内部 RabbitMQ → 删除 MNS 消息】。
 *
 * <p><b>为什么不直接落库</b>：转投后可复用既有幂等（MqIdempotent）、
 * 延迟重试与死信队列；第三方网络抖动或落库瞬时失败都不会丢数据。
 *
 * <p><b>可靠性口径（重要）</b>：
 * <ul>
 *   <li>只有「RabbitMQ 发布确认成功」之后才删除 MNS 消息；</li>
 *   <li>转投失败不删除消息，MNS 可见性超时后自动重投（at-least-once）；</li>
 *   <li>报文解析失败属于「永远重投也不会成功」的坏消息，
 *       写入 device_message_error 留档后再删除，避免无限循环；</li>
 *   <li>MQ 侧再用业务唯一键兜底去重，重复投递不会产生重复记录。</li>
 * </ul>
 *
 * <p><b>开关</b>：{@code app.mns.enabled}（环境变量 MNS_ENABLED）默认 false，
 * 未拿到 accessKeyId / secretAccessKey / queueName 时消费端不启动，应用照常运行；
 * 一旦显式开启却缺配置，则拒绝启动（fail-fast，避免「以为在消费其实没消费」）。
 */
@Component
@ConditionalOnProperty(prefix = "app.mns", name = "enabled", havingValue = "true")
public class MnsPoller implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MnsPoller.class);

    /** 队列自动自检周期：10 分钟 */
    private static final long SELF_CHECK_INTERVAL_MILLIS = 10 * 60 * 1000L;

    private final MnsProperties properties;
    private final MnsMessageParser parser;
    private final MqProducer mqProducer;
    private final DeviceService deviceService;
    /** 队列自检：消费端把心跳/计数上报给它，并每 10 分钟做一次自动自检 */
    private final MnsQueueStatusProvider statusProvider;

    private volatile boolean running;
    private Thread worker;
    /** 上次自动自检时间（仅轮询线程读写） */
    private long lastSelfCheck;

    public MnsPoller(MnsProperties properties,
                     MnsMessageParser parser,
                     MqProducer mqProducer,
                     DeviceService deviceService,
                     MnsQueueStatusProvider statusProvider) {
        this.properties = properties;
        this.parser = parser;
        this.mqProducer = mqProducer;
        this.deviceService = deviceService;
        this.statusProvider = statusProvider;
    }

    @Override
    public void start() {
        validateConfiguration();
        running = true;
        statusProvider.markRunning(true);
        worker = new Thread(this::loop, "mns-device-poller");
        worker.setDaemon(true);
        worker.start();
        log.info("MNS 设备队列消费端已启动 endpoint={} queue={} waitSeconds={}",
                properties.getEndpoint(), properties.getQueueName(), properties.getWaitSeconds());
    }

    @Override
    public void stop() {
        running = false;
        statusProvider.markRunning(false);
        statusProvider.markConnected(false);
        Thread current = worker;
        if (current != null) {
            current.interrupt();
        }
        log.info("MNS 设备队列消费端已停止");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 最晚启动、最先停止：保证应用其余组件就绪后再开始消费，
     * 停机时先停止拉取，避免关机过程中持续投递。
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    private void validateConfiguration() {
        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(properties.getEndpoint())) {
            missing.add("MNS_ENDPOINT");
        }
        if (!StringUtils.hasText(properties.getQueueName())) {
            missing.add("MNS_QUEUE");
        }
        if (!StringUtils.hasText(properties.getAccessKeyId())) {
            missing.add("MNS_AK");
        }
        if (!StringUtils.hasText(properties.getAccessKeySecret())) {
            missing.add("MNS_SK");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "已启用设备队列消费端（app.mns.enabled=true），但缺少配置：" + String.join(", ", missing)
                            + "。请先注入密钥与队列信息（部署侧 /opt/wuling/app/secrets.env），"
                            + "或保持 app.mns.enabled=false。");
        }
    }

    private void loop() {
        MNSClient client = null;
        CloudQueue queue = null;
        while (running) {
            try {
                if (queue == null) {
                    client = new CloudAccount(properties.getAccessKeyId(),
                            properties.getAccessKeySecret(),
                            properties.getEndpoint()).getMNSClient();
                    queue = client.getQueueRef(properties.getQueueName());
                    statusProvider.markConnected(true);
                    log.info("MNS 连接就绪 queue={}", properties.getQueueName());
                }
                // 每 10 分钟自检一次队列：把「对方有没有在推」写进日志，不依赖有人打开后台
                maybeSelfCheck();
                // MNS 长轮询等待秒数上限 30，配置写大了会被服务端拒绝，这里做一次钳制
                Message message = queue.popMessage(Math.min(30, Math.max(1, properties.getWaitSeconds())));
                statusProvider.markPolled();
                if (message == null) {
                    // 长轮询超时无消息，属正常情况
                    continue;
                }
                handle(queue, message);
            } catch (Exception e) {
                log.error("MNS 轮询异常，{} ms 后重连重试：{}", properties.getErrorBackoffMs(), e.getMessage(), e);
                statusProvider.markPollError(e.getMessage());
                statusProvider.markConnected(false);
                closeQuietly(client);
                client = null;
                queue = null;
                if (!sleep(properties.getErrorBackoffMs())) {
                    break;
                }
            }
        }
        closeQuietly(client);
    }

    /** 自动自检节流：10 分钟一次，失败也只在日志中出现一行 WARN。 */
    private void maybeSelfCheck() {
        long now = System.currentTimeMillis();
        if (now - lastSelfCheck < SELF_CHECK_INTERVAL_MILLIS) {
            return;
        }
        lastSelfCheck = now;
        statusProvider.selfCheck();
    }

    private void handle(CloudQueue queue, Message message) {
        String messageId = message.getMessageId();
        statusProvider.markMessageReceived();
        try {
            DeviceMessageEnvelope envelope = parser.parse(bodyCandidates(message), messageId);
            mqProducer.sendWithId(MqConstants.DEVICE_MESSAGE_ROUTING_KEY, envelope, messageId, envelope.getDeviceSn());
            queue.deleteMessage(message.getReceiptHandle());
            statusProvider.markForwarded(true, null);
            log.info("MNS 消息已转投 MQ messageId={} type={} deviceSn={}",
                    messageId, envelope.getType(), envelope.getDeviceSn());
        } catch (MnsParseException e) {
            deviceService.recordMessageError(messageId, e.getMessagetype(), rawBody(message), e.getMessage());
            statusProvider.markParseFailed("报文解析失败：" + e.getMessage());
            try {
                // 坏消息删除前必须已留档，否则会在 MNS 里被无限重投并刷满日志
                queue.deleteMessage(message.getReceiptHandle());
            } catch (Exception deleteError) {
                log.warn("异常报文删除失败，将由 MNS 重投 messageId={} reason={}", messageId, deleteError.getMessage());
            }
            log.error("MNS 设备报文解析失败，已留档 messageId={} messagetype={} reason={}",
                    messageId, e.getMessagetype(), e.getMessage());
        } catch (Exception e) {
            // 转投/删除失败：不 ack，MNS 可见性超时后重投；此处只记录，不吞异常语义
            statusProvider.markForwarded(false, e.getMessage());
            log.error("MNS 设备消息处理失败，等待 MNS 重投 messageId={} reason={}", messageId, e.getMessage(), e);
        }
    }

    /**
     * SDK 不同取值方法拿到的报文形态不同（原始 Base64 或已解码文本），
     * 全部交给解析器按「明文 JSON → Base64 解码」自动识别。
     */
    private List<String> bodyCandidates(Message message) {
        List<String> candidates = new ArrayList<>(3);
        candidates.add(message.getMessageBodyAsRawString());
        candidates.add(message.getMessageBody());
        candidates.add(message.getMessageBodyAsString());
        return candidates;
    }

    private String rawBody(Message message) {
        for (String candidate : bodyCandidates(message)) {
            if (StringUtils.hasText(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean sleep(long millis) {
        try {
            Thread.sleep(Math.max(0L, millis));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void closeQuietly(MNSClient client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (Exception e) {
            log.debug("关闭 MNS 客户端异常：{}", e.getMessage());
        }
    }
}
