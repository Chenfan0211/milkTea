package com.wuling.user.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 舌象第三方服务配置。
 *
 * <p>appSecret 只允许通过环境变量 TONGUE_APP_SECRET 注入，仓库只保留空占位。
 * appId 当前不参与鉴权，也不发送请求头，仅保留配置位以兼容后续协议调整。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.tongue")
public class TongueProperties {

    private String baseUrl = "https://linkhealth.tcmbrain.com/hzsg";
    private String appKey;
    private String appSecret;
    private String appId;

    /** 连接第三方服务超时，默认 10 秒。 */
    private Duration connectTimeout = Duration.ofSeconds(10);

    /** 舌象检测读取超时，默认 60 秒。 */
    private Duration detectionReadTimeout = Duration.ofSeconds(60);

    /** 报告查询读取超时，默认 30 秒。 */
    private Duration reportReadTimeout = Duration.ofSeconds(30);

    /** file-service 内部服务名，仅供内部 RestClient 使用。 */
    private String fileBaseUrl = "http://wuling-file-service";
}
