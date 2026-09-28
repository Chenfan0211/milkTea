# Redis 多库命名空间实践

> 场景：多个业务模块（登录态、验证码、缓存、业务锁）共享同一个 Redis 实例，
> 需要逻辑隔离，避免 key 混乱、互相覆盖，同时保留将来上 Redis Cluster 的能力。
> 核心思路：**逻辑命名空间（key 前缀）+ 可切换后端**——单机模式映射到不同 db，
> cluster 模式自动降级为纯前缀隔离（Cluster 只支持 db0）。

## 一、核心思路

```
命名空间枚举（AUTH / SMS / CACHE / BIZ）
  ├─ 每个命名空间有：key 前缀 + 默认库号
  ├─ single 模式：按库号映射到不同 db（物理隔离）
  └─ cluster 模式：库号失效，全部走 db0 + key 前缀（逻辑隔离）
```

关键点：
1. **key 前缀始终保留**（无论哪种模式）——保证可观测性（`SCAN prefix:*`）与集群兼容。
2. **库号仅单机/主从模式生效**——Redis Cluster 只支持 db0，`SELECT n` 会报错，所以必须留 cluster 降级路径。
3. **每个库号缓存独立的模板/连接**——避免重复建连。

## 二、可复制的代码骨架

### 命名空间枚举

```java
public enum RedisNamespace {

    AUTH("app:auth:", "auth"),   // 登录态
    SMS("app:sms:", "sms"),       // 验证码与限流计数
    CACHE("app:cache:", "cache"), // 通用缓存
    BIZ("app:biz:", "biz");       // 业务数据（锁、幂等键）

    private final String prefix;      // key 前缀
    private final String databaseKey; // 配置中对应的库号 key

    RedisNamespace(String prefix, String databaseKey) {
        this.prefix = prefix;
        this.databaseKey = databaseKey;
    }

    public String prefix() { return prefix; }
    public String databaseKey() { return databaseKey; }

    /** 拼接完整 key */
    public String key(String suffix) { return prefix + suffix; }
}
```

### 配置属性

```java
@Component
@ConfigurationProperties(prefix = "app.redis")
public class RedisProperties {

    private String mode = "single";                 // single | cluster
    private Map<String, Integer> database = new HashMap<>();

    public boolean isSingleMode() {
        return !"cluster".equalsIgnoreCase(mode);
    }

    /** 取命名空间对应库号（cluster 模式恒为 0） */
    public int databaseOf(RedisNamespace namespace) {
        if (!isSingleMode()) return 0;
        Integer db = database.get(namespace.databaseKey());
        return db == null ? 0 : db;
    }
    // getter/setter 省略
}
```

### 多库模板管理器

```java
@Component
public class RedisManager {

    private final RedisConnectionFactory baseFactory;
    private final RedisProperties properties;
    private final Map<Integer, StringRedisTemplate> templates = new ConcurrentHashMap<>();

    public RedisManager(RedisConnectionFactory baseFactory, RedisProperties properties) {
        this.baseFactory = baseFactory;
        this.properties = properties;
        log.info("Redis mode={} databaseMapping={}", properties.getMode(), properties.getDatabase());
    }

    /** 按命名空间取模板 */
    public StringRedisTemplate template(RedisNamespace namespace) {
        return templateForDatabase(properties.databaseOf(namespace));
    }

    /** 按库号取模板（缓存复用，避免重复建连） */
    public StringRedisTemplate templateForDatabase(int database) {
        return templates.computeIfAbsent(database, this::createTemplate);
    }

    /** 拼接带命名空间前缀的 key */
    public String key(RedisNamespace namespace, String suffix) {
        return namespace.key(suffix);
    }

    private StringRedisTemplate createTemplate(int database) {
        RedisConnectionFactory factory = baseFactory;
        if (properties.isSingleMode() && baseFactory instanceof LettuceConnectionFactory lettuce) {
            // 为不同库号创建独立连接工厂（Lettuce 连接与库号绑定）
            LettuceConnectionFactory clone = new LettuceConnectionFactory(
                    lettuce.getStandaloneConfiguration(), lettuce.getClientConfiguration());
            clone.setDatabase(database);
            clone.setShareNativeConnection(false);
            clone.afterPropertiesSet();
            factory = clone;
        }
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }
}
```

### 使用方式

```java
// 注入 RedisManager，按命名空间取模板 + 拼 key
private final RedisManager redisManager;

StringRedisTemplate redis = redisManager.template(RedisNamespace.SMS);
String key = redisManager.key(RedisNamespace.SMS, "code:" + phone);
redis.opsForValue().set(key, code, Duration.ofMinutes(5));
```

### 配置项

```yaml
app:
  redis:
    mode: ${REDIS_MODE:single}        # single | cluster
    database:
      auth:  ${REDIS_DB_AUTH:0}
      sms:   ${REDIS_DB_SMS:1}
      cache: ${REDIS_DB_CACHE:2}
      biz:   ${REDIS_DB_BIZ:3}
```

## 三、关键决策与权衡

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 分库 vs 集群 | 命名空间 + 可切换后端 | Redis Cluster 只支持 db0，物理分库与集群互斥 |
| key 前缀 | 始终保留 | 保证 `SCAN` 可观测 + 集群兼容 |
| cluster 降级 | 库号失效，走前缀隔离 | 零改造上集群，改一个环境变量即可 |
| 模板复用 | ConcurrentHashMap 缓存 | 每个库号只建一次连接，避免重复建连 |

## 四、适用 / 不适用

**适用**：
- 单机/主从 Redis，希望物理隔离不同业务的数据
- 计划将来迁移 Redis Cluster

**注意**：
- 一旦上 Cluster，db 隔离自动失效，只能靠 key 前缀——所以**从一开始就规范 key 前缀**最重要。

## 五、验证方法

```bash
# 单机模式：验证数据真的分库
redis-cli -n 0 KEYS 'app:auth:*'   # 登录态在 db0
redis-cli -n 1 KEYS 'app:sms:*'    # 验证码在 db1

# 用 TTL 差值判断数据写入时间（排查分库失效的误判）
redis-cli -n 0 TTL 'app:sms:*'   # 若 TTL 明显小于 db1，说明是历史脏数据
```

> 排查技巧：**用 TTL 差值判断数据写入时间**，比翻日志快——不同库同前缀的 key，若 TTL 差约一个窗口，说明是旧代码写入的历史残留，而非分库失效。