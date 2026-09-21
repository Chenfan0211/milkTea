# Redis 分库方案（路径 B：分库 + 保留集群能力）

> 决策：B —— single 模式按命名空间分库，cluster 模式自动退化为前缀隔离

## 一、为什么不能「分库 + 集群」同时纯物理实现

Redis 的硬约束：

| 模式 | 多库支持 |
|------|---------|
| 单机 / 主从 | ✅ 支持 db0–db15 |
| **Cluster** | ❌ **只支持 db 0**，`SELECT n` 直接报错 |

所以「真分库」和「上集群」在 Redis 里是**互斥**的。路径 B 的解法是**逻辑命名空间 + 可切换后端**：

- `mode=single`：命名空间映射到不同 db（满足分库诉求）
- `mode=cluster`：库号自动失效，全部走 db0，靠 key 前缀隔离（零改造上集群）

## 二、命名空间设计

| 命名空间 | Key 前缀 | 用途 | 默认库号 |
|---------|---------|------|---------|
| `AUTH` | `wuling:auth:` | 微信 session_key、用户定位 | db0 |
| `SMS` | `wuling:sms:` | 验证码、重发间隔、日限计数 | db1 |
| `CACHE` | `wuling:cache:` | 通用缓存（预留） | db2 |
| `BIZ` | `wuling:biz:` | 业务数据（预留：库存锁、幂等键） | db3 |

> **前缀始终保留**，无论哪种模式——保证可观测性（可 `SCAN wuling:sms:*` 排查/清理）与集群兼容。

## 三、配置

```yaml
app:
  redis:
    mode: ${REDIS_MODE:single}   # single | cluster
    database:
      auth:  ${REDIS_DB_AUTH:0}
      sms:   ${REDIS_DB_SMS:1}
      cache: ${REDIS_DB_CACHE:2}
      biz:   ${REDIS_DB_BIZ:3}
```

全部支持环境变量覆盖，部署时无需改代码。

## 四、代码使用方式

```java
// 1) 注入 RedisManager（替代 StringRedisTemplate）
private final RedisManager redisManager;

// 2) 取命名空间模板
StringRedisTemplate redis = redisManager.template(RedisNamespace.SMS);

// 3) 拼接带前缀的 key
String key = RedisNamespace.SMS.key("code:" + phone);

redis.opsForValue().set(key, code, Duration.ofMinutes(5));
```

`RedisManager` 内部为每个库号缓存独立的 `StringRedisTemplate`（各自持有连接工厂），避免重复建连。

## 五、验证结果

### single 模式：数据真的分库
```
--- db0 ---
    wuling:auth:user:location:1          ← 登录态
--- db1 ---
    wuling:sms:code:13500135000          ← 验证码
    wuling:sms:cooldown:13500135000      ← 重发间隔
    wuling:sms:daily:ip:127.0.0.1        ← 限流计数
    wuling:sms:daily:phone:13500135000
--- db2 ---  (空，CACHE 预留)
--- db3 ---  (空，BIZ 预留)
```

启动日志：
```
RedisManager : Redis mode=single databaseMapping={sms=1, cache=2, biz=3, auth=0}
```

### cluster 模式：自动降级为 db0（集群安全）
```
RedisManager : Redis mode=cluster databaseMapping={sms=1, cache=2, biz=3, auth=0}
验证：所有 key 均落 db0，不报错、不丢数据
```

> 这正是路径 B 的价值：**将来上集群只需改一个环境变量 `REDIS_MODE=cluster`**。

### 功能回归（8/8）
```
未登录访问订单      code=8888 ✅
登录后访问订单      code=0    ✅
菜单（公开）        code=0    ✅
运营配置（公开）    code=0    ✅
城市（公开）        code=0    ✅
发送验证码          code=0    ✅
60s 重发限流        code=400  ✅
定位上报            code=0    ✅
```

### 回归
| 项目 | 结果 |
|------|------|
| 后端 `mvn test` | 16/16 通过 |
| 数据库一致性校验 | 3/3 通过 |

## 六、排查过程中的一个误判（已澄清）

首轮验证时发现 db0 里出现 `wuling:sms:*`，一度怀疑分库失效。通过 **TTL 对比**确认：

- db0 的 key TTL ≈ 82918s（约 23h）
- db1 的 key TTL ≈ 86353s（约 24h）

TTL 差约 1 小时，说明 db0 那批是**改造前**旧代码写入的历史数据，并非分库失效。清理后分布完全正确。

> 排查手法值得记录：**用 TTL 差值判断数据写入时间**，比翻日志快。

## 七、改动文件

**新增**
- `common/redis/RedisNamespace.java`（命名空间常量）
- `common/redis/RedisProperties.java`（多库配置）
- `common/redis/RedisManager.java`（多库模板管理）

**修改**
- `application.yml`（redis 配置块）
- `user/service/SmsCodeService.java`（改用 SMS 命名空间）
- `user/service/MiniAppAuthService.java`（改用 AUTH 命名空间）

## 八、后续扩展

| 场景 | 做法 |
|------|------|
| 新增业务缓存 | 用 `RedisNamespace.CACHE`，无需改配置结构 |
| 上 Redis 集群 | 设 `REDIS_MODE=cluster`，代码零改动 |
| 调整库号分配 | 改环境变量 `REDIS_DB_*` |
| 按前缀清理 | `SCAN` + `wuling:sms:*` |
