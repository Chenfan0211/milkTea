package com.wuling.trade.pay.yeepay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 易宝配置加载器：启动完成后从 server 拉取支付配置。
 *
 * <p><b>为什么不用环境变量</b>：appKey / 商编 / 私钥都是运营可配置项
 * （换商户号、轮换密钥、多环境切换都需要改），放环境变量意味着每次
 * 变更都要改部署配置并重启。改存 {@code platform_profile.pay_config} 后，
 * 运营在后台维护即可。
 *
 * <p><b>生效时机：启动时拉取一次（重启生效）</b>。已确认这是可接受的取舍——
 * 支付配置属于低频变更，重启成本可接受，换来实现简单与行为确定
 * （避免热加载带来的「配置半更新」中间态）。
 *
 * <p><b>为什么是 ApplicationReadyEvent 而非 PostConstruct</b>：
 * Nacos 服务发现与负载均衡器在 ApplicationReady 时才完全就绪，
 * 过早调用会因服务未注册而失败。
 *
 * <p><b>失败不阻断启动</b>：配置缺失只影响易宝通道，不应拖垮整个交易服务
 * （微信收款仍可用）。失败时记 ERROR，且 {@code YeepayProperties#isEnabled()}
 * 会因缺 appKey 而返回 false，各 Bean 自然降级。
 */
@Component
public class YeepayConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(YeepayConfigLoader.class);

    private final YeepayProperties properties;
    private final RestClient serverInternalRestClient;
    private final ObjectMapper objectMapper;

    public YeepayConfigLoader(YeepayProperties properties,
                              @Qualifier("serverInternalRestClient") RestClient serverInternalRestClient,
                              ObjectMapper objectMapper) {
        this.properties = properties;
        this.serverInternalRestClient = serverInternalRestClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 启动完成后加载配置。
     *
     * <p>仅在 {@code app.pay.yeepay.channel=yeepay} 时才去拉取——
     * 未启用易宝的环境不必依赖 server 的这个接口。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        if (!properties.isChannelEnabled()) {
            log.info("易宝通道未启用，跳过配置加载");
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = serverInternalRestClient.get()
                    .uri("/internal/platform/yeepay-config?subjectId={id}", 1L)
                    .retrieve()
                    .body(Map.class);
            if (response == null) {
                log.error("易宝配置加载失败：server 无响应。"
                        + "易宝收款将不可用，微信通道不受影响。");
                return;
            }
            if (!Boolean.TRUE.equals(response.get("complete"))) {
                log.error("易宝配置不完整：{}。请到运营后台「平台主体」页填写易宝配置。",
                        response.get("reason"));
                return;
            }
            applyTo(response);
        } catch (Exception e) {
            log.error("易宝配置加载异常：{}。易宝收款将不可用，微信通道不受影响。",
                    e.getMessage());
        }
    }

    /**
     * 把 DB 配置写入 {@link YeepayProperties}。
     *
     * <p>用 {@code set} 而非替换整个对象：{@code YeepayProperties} 是
     * Spring 单例，已有 Bean 持有它的引用，替换对象不会生效。
     */
    private void applyTo(Map<String, Object> response) {
        String appKey = str(response.get("appKey"));
        String merchantNo = str(response.get("merchantNo"));
        String platformNo = str(response.get("platformMerchantNo"));
        String privateKey = str(response.get("privateKey"));
        String yopPublicKey = str(response.get("yopPublicKey"));

        properties.setAppKey(appKey);
        properties.setMerchantNo(merchantNo);
        properties.setPlatformMerchantNo(platformNo);
        properties.setPrivateKey(privateKey);
        if (yopPublicKey != null && !yopPublicKey.isBlank()) {
            properties.setYopPublicKey(yopPublicKey);
        }

        // 只记录「是否配置」，绝不打印密钥与商编值
        log.info("易宝配置加载完成：appKey={} 服务商商编={} 平台商商编={} 平台公钥={}",
                appKey != null ? "已配置" : "缺失",
                merchantNo != null ? "已配置" : "缺失",
                platformNo != null && !platformNo.isBlank() ? "已配置" : "缺失",
                yopPublicKey != null && !yopPublicKey.isBlank() ? "已配置" : "将远程拉取");

        if (platformNo == null || platformNo.isBlank()) {
            log.error("易宝平台商商编未配置：平台份额将无法分账，订单会卡在部分分账。"
                    + "请向易宝开通「平台商」子商户后在后台配置。");
        }
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
