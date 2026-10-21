package com.wuling.device.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 阿里云轻量消息队列（原 MNS）对接配置。
 *
 * <p>对应 {@code app.mns.*}，全部支持环境变量覆盖，默认【关闭】：
 * 未拿到 accessKeyId / secretAccessKey / queueName 之前，
 * {@code MNS_ENABLED=false}，消费端不启动，应用照常运行。</p>
 *
 * <p>密钥只从部署环境注入（/opt/wuling/app/secrets.env），不写入仓库。</p>
 */
@Component
@ConfigurationProperties(prefix = "app.mns")
public class MnsProperties {

    /** 是否启用 MNS 消费端（默认关闭；拿到密钥后置 true） */
    private boolean enabled;

    /** 队列 endpoint，形如 http://1862421895270680.mns.cn-shanghai.aliyuncs.com */
    private String endpoint;

    /** 队列名 */
    private String queueName;

    /** AccessKeyId */
    private String accessKeyId;

    /** AccessKeySecret */
    private String accessKeySecret;

    /** 长轮询等待秒数（MNS 上限 30） */
    private int waitSeconds = 30;

    /** 单轮异常后的退避时间（毫秒），避免网络/权限异常时打满日志 */
    private long errorBackoffMs = 15_000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getQueueName() {
        return queueName;
    }

    public void setQueueName(String queueName) {
        this.queueName = queueName;
    }

    public String getAccessKeyId() {
        return accessKeyId;
    }

    public void setAccessKeyId(String accessKeyId) {
        this.accessKeyId = accessKeyId;
    }

    public String getAccessKeySecret() {
        return accessKeySecret;
    }

    public void setAccessKeySecret(String accessKeySecret) {
        this.accessKeySecret = accessKeySecret;
    }

    public int getWaitSeconds() {
        return waitSeconds;
    }

    public void setWaitSeconds(int waitSeconds) {
        this.waitSeconds = waitSeconds;
    }

    public long getErrorBackoffMs() {
        return errorBackoffMs;
    }

    public void setErrorBackoffMs(long errorBackoffMs) {
        this.errorBackoffMs = errorBackoffMs;
    }
}
