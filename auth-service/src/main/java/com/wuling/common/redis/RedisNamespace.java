package com.wuling.common.redis;

/**
 * Redis 命名空间。
 *
 * 设计说明（重要）：
 * - Redis Cluster 只支持 db 0，若代码强依赖 SELECT n，将来上集群会直接不可用；
 * - 因此这里统一「逻辑命名空间」入口：
 *   · single 模式：按 app.redis.database.* 映射到不同 db（满足分库诉求）；
 *   · cluster 模式：库号自动失效，退化为 key 前缀隔离（保证可扩展）。
 * - 无论哪种模式，key 前缀都保留，便于监控、排查与按前缀清理。
 */
public enum RedisNamespace {

    /** 登录态：微信 session_key、用户定位 */
    AUTH("wuling:auth:", "auth"),

    /** 验证码与限流计数 */
    SMS("wuling:sms:", "sms"),

    /** 通用缓存（预留） */
    CACHE("wuling:cache:", "cache"),

    /** 业务数据（预留：如库存锁、幂等键） */
    BIZ("wuling:biz:", "biz");

    private final String prefix;
    private final String databaseKey;

    RedisNamespace(String prefix, String databaseKey) {
        this.prefix = prefix;
        this.databaseKey = databaseKey;
    }

    /** key 前缀 */
    public String prefix() {
        return prefix;
    }

    /** 对应配置中 app.redis.database.{databaseKey} 的库号 */
    public String databaseKey() {
        return databaseKey;
    }

    /** 拼接完整 key */
    public String key(String suffix) {
        return prefix + suffix;
    }
}
