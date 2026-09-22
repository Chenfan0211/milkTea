package com.wuling.common.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Redis 多库配置。
 *
 * app.redis.mode=single  ：按 database 映射连接不同 db（默认）
 * app.redis.mode=cluster ：忽略 database（集群仅支持 db0），统一走 key 前缀
 */
@Component
@ConfigurationProperties(prefix = "app.redis")
public class RedisProperties {

    /** single | cluster */
    private String mode = "single";

    /** 各命名空间对应的库号：auth / sms / cache / biz */
    private Map<String, Integer> database = new HashMap<>();

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public Map<String, Integer> getDatabase() {
        return database;
    }

    public void setDatabase(Map<String, Integer> database) {
        this.database = database;
    }

    public boolean isSingleMode() {
        return !"cluster".equalsIgnoreCase(mode);
    }

    /** 取命名空间对应库号（cluster 模式恒为 0） */
    public int databaseOf(RedisNamespace namespace) {
        if (!isSingleMode()) {
            return 0;
        }
        Integer db = database.get(namespace.databaseKey());
        return db == null ? 0 : db;
    }
}
