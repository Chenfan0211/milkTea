package com.wuling.system.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.common.security.SensitiveCrypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 易宝支付配置内部接口（供 trade-service 拉取）。
 *
 * <p><b>为什么由 server 提供</b>：支付配置存在 {@code platform_profile.pay_config}，
 * 属 server 的数据库。trade-service 需要它来初始化 YOP SDK，
 * 通过内部接口索取可避免 trade 反向依赖 platform_profile 表结构。
 *
 * <p><b>安全</b>：
 * <ul>
 *   <li>{@code /internal/**} 不经网关路由，各服务仅监听 127.0.0.1；</li>
 *   <li>私钥<b>在此处解密</b>后返回——trade-service 需要明文才能签名。
 *       这是必要的取舍：加密只保护「静态存储」，不保护「运行期内存」；</li>
 *   <li>本接口不记录任何配置值到日志，只记「是否完整」。</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal/platform")
public class YeepayConfigInternalController {

    private static final Logger log = LoggerFactory.getLogger(YeepayConfigInternalController.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public YeepayConfigInternalController(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 读取易宝配置（私钥已解密）。
     *
     * @param subjectId 平台档案主体 id，默认 1
     * @return { appKey, merchantNo, platformMerchantNo, privateKey, yopPublicKey, complete }
     */
    @GetMapping("/yeepay-config")
    public Map<String, Object> yeepayConfig(@RequestParam(defaultValue = "1") long subjectId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select pay_config from platform_profile where subject_id = ? and deleted = 0",
                subjectId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (rows.isEmpty() || rows.get(0).get("pay_config") == null) {
            log.warn("平台档案不存在或未配置 pay_config subjectId={}", subjectId);
            return incomplete("平台档案未配置");
        }

        try {
            JsonNode node = objectMapper.readTree(rows.get(0).get("pay_config").toString());
            String appKey = text(node, "yeepayAppKey");
            String merchantNo = text(node, "yeepayMerchantNo");
            String platformNo = text(node, "yeepayPlatformMerchantNo");
            String encPrivateKey = text(node, "yeepayPrivateKey");
            String yopPublicKey = text(node, "yeepayYopPublicKey");

            if (appKey == null || merchantNo == null || encPrivateKey == null) {
                log.warn("易宝配置不完整 subjectId={} appKey={} merchantNo={} 私钥={}",
                        subjectId, appKey != null, merchantNo != null, encPrivateKey != null);
                return incomplete("易宝配置不完整（需要 appKey / merchantNo / privateKey）");
            }

            String privateKey;
            try {
                privateKey = SensitiveCrypto.decrypt(encPrivateKey);
            } catch (Exception e) {
                // 解密失败通常是 APP_SENSITIVE_KEY 变更了——必须明确报错，
                // 否则会用空密钥去请求易宝，得到难懂的签名错误
                log.error("易宝私钥解密失败（APP_SENSITIVE_KEY 是否变更过？）subjectId={}", subjectId);
                return incomplete("私钥解密失败，请重新保存私钥或检查 APP_SENSITIVE_KEY");
            }

            result.put("appKey", appKey);
            result.put("merchantNo", merchantNo);
            result.put("platformMerchantNo", platformNo);
            result.put("privateKey", privateKey);
            result.put("yopPublicKey", yopPublicKey);
            result.put("complete", true);
            // 只记「已加载」，绝不打印配置值
            log.info("易宝配置已下发 subjectId={} 平台商商编={}", subjectId,
                    platformNo == null ? "未配置" : "已配置");
            return result;
        } catch (Exception e) {
            log.error("解析易宝配置失败 subjectId={} err={}", subjectId, e.getMessage());
            return incomplete("配置解析失败：" + e.getMessage());
        }
    }

    private Map<String, Object> incomplete(String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("complete", false);
        result.put("reason", reason);
        return result;
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}
