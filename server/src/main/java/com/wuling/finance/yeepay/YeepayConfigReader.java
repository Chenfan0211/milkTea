package com.wuling.finance.yeepay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 易宝支付配置的读取入口（server 侧）。
 *
 * <p><b>为什么需要这个类</b>：易宝配置存在 {@code platform_profile.pay_config}，
 * 由运营在后台维护。{@code trade-service} 有自己的 {@code YeepayConfigLoader}
 * 从内部接口拉取；但 {@code server} 进程里的分账/退款逻辑（如
 * {@code DivideRefundService} 需要 {@code platformMerchantNo} 作为
 * 归集的 {@code ledgerNo}）同样需要它。
 *
 * <p>原实现用 {@code @Value("${app.pay.yeepay.platform-merchant-no:}")} 读环境变量，
 * 与「配置存数据库」的既定方案冲突：数据库里配好了，server 依然拿到空字符串，
 * 导致退款归集的 {@code ledgerNo} 为空、易宝拒绝请求。这里改为直接读库。
 *
 * <p><b>缓存策略</b>：配置属低频变更（换商户号/轮换密钥），但
 * 退款归集是逐单调用。若每次查库会放大 DB 压力，故做内存缓存；
 * 调用 {@link #invalidate()} 可主动失效（后台保存配置后应调用，
 * 或等 TTL 自然过期）。
 *
 * <p><b>失败语义</b>：查不到或解析失败时返回 {@code null}，
 * 由调用方决定降级方式（分账场景应报错，不能静默用空串）。
 */
@Service
public class YeepayConfigReader {

    private static final Logger log = LoggerFactory.getLogger(YeepayConfigReader.class);

    /** 平台档案主体 id（与 PlatformProfileController.PLATFORM_SUBJECT_ID 一致）。 */
    private static final long PLATFORM_SUBJECT_ID = 1L;

    private static final long CACHE_TTL_MS = 60_000L;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /** 缓存值 + 缓存时间戳；用不可变快照避免读到半个对象。 */
    private final AtomicReference<Snapshot> cache = new AtomicReference<>();

    public YeepayConfigReader(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 读取平台商商编（分账/归集的 {@code ledgerNo}）。
     *
     * @return 平台商商编；未配置返回 {@code null}（调用方必须显式处理，
     *         不能当成空串传给易宝）
     */
    public String platformMerchantNo() {
        Config cfg = load();
        return cfg == null ? null : blankToNull(cfg.platformMerchantNo);
    }

    /** 读取服务商主商编（易宝请求的 {@code parentMerchantNo}）。 */
    public String merchantNo() {
        Config cfg = load();
        return cfg == null ? null : blankToNull(cfg.merchantNo);
    }

    /** 读取 appKey。 */
    public String appKey() {
        Config cfg = load();
        return cfg == null ? null : blankToNull(cfg.appKey);
    }

    /** 易宝通道是否已具备发起请求的基本条件（appKey + 商编）。 */
    public boolean isConfigured() {
        Config cfg = load();
        return cfg != null
                && blankToNull(cfg.appKey) != null
                && blankToNull(cfg.merchantNo) != null;
    }

    /** 配置变更后主动失效缓存（后台保存配置时可调用）。 */
    public void invalidate() {
        cache.set(null);
    }

    private Config load() {
        Snapshot snapshot = cache.get();
        long now = System.currentTimeMillis();
        if (snapshot != null && now - snapshot.loadedAt < CACHE_TTL_MS) {
            return snapshot.config;
        }
        Config fresh = readFromDb();
        cache.set(new Snapshot(fresh, now));
        return fresh;
    }

    private Config readFromDb() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select pay_config from platform_profile where subject_id = ? and deleted = 0",
                PLATFORM_SUBJECT_ID);
        if (rows.isEmpty() || rows.get(0).get("pay_config") == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(rows.get(0).get("pay_config").toString());
            Config cfg = new Config();
            cfg.appKey = text(node, "yeepayAppKey");
            cfg.merchantNo = text(node, "yeepayMerchantNo");
            cfg.platformMerchantNo = text(node, "yeepayPlatformMerchantNo");
            return cfg;
        } catch (Exception e) {
            // 只记异常类型，不打印 pay_config 内容（含加密私钥）
            log.error("解析 platform_profile.pay_config 失败：{}", e.getClass().getSimpleName());
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** 解析出的易宝配置快照。 */
    private static class Config {
        String appKey;
        String merchantNo;
        String platformMerchantNo;
    }

    private record Snapshot(Config config, long loadedAt) {
    }
}
