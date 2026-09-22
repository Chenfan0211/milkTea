package com.wuling.common.security;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败计数器（P2 安全加固）。
 *
 * <p>背景：{@code /auth/login} 无任何限流，攻击者可无限次尝试口令（暴力破解）。
 *
 * <p>设计：与具体存储解耦 —— 本类只负责"计数与锁定"的<b>策略</b>，
 * 数据存取通过 {@link Store} 接口注入。这样：
 * <ul>
 *   <li>生产用 Redis 实现，多实例部署下计数共享；</li>
 *   <li>单测用内存实现，无需依赖 Redis。</li>
 * </ul>
 *
 * <p>锁定策略：
 * <ul>
 *   <li>连续失败 {@value #MAX_FAILURES} 次 → 锁定 {@value #LOCK_MINUTES} 分钟；</li>
 *   <li>计数与锁定按「用户名 + IP」双维度，避免只按用户名被用来锁死他人账号（DoS）；</li>
 *   <li>登录成功立即清零该维度计数。</li>
 * </ul>
 */
public class LoginAttemptGuard {

    /** 允许的连续失败次数 */
    public static final int MAX_FAILURES = 5;

    /** 锁定时长（分钟） */
    public static final long LOCK_MINUTES = 15;

    /** 失败计数的统计窗口（分钟） */
    public static final long WINDOW_MINUTES = 15;

    private final Store store;

    public LoginAttemptGuard(Store store) {
        this.store = store;
    }

    /**
     * 检查是否处于锁定状态；被锁定则抛出异常（由调用方转换为业务错误）。
     *
     * @param keys 需要检查的维度（如 用户名、IP）
     * @return 剩余锁定秒数；0 表示未锁定
     */
    public long lockedSeconds(String... keys) {
        long max = 0;
        for (String key : keys) {
            Long ttl = store.ttl(lockKey(key));
            if (ttl != null && ttl > 0) {
                max = Math.max(max, ttl);
            }
        }
        return max;
    }

    /**
     * 记录一次失败，达到阈值则锁定。
     *
     * @return true = 本次失败导致账号/来源被锁定
     */
    public boolean recordFailure(String... keys) {
        boolean locked = false;
        for (String key : keys) {
            long count = store.increment(countKey(key), Duration.ofMinutes(WINDOW_MINUTES));
            if (count >= MAX_FAILURES) {
                store.set(lockKey(key), "1", Duration.ofMinutes(LOCK_MINUTES));
                locked = true;
            }
        }
        return locked;
    }

    /** 登录成功：清空计数与锁定 */
    public void recordSuccess(String... keys) {
        for (String key : keys) {
            store.delete(countKey(key));
            store.delete(lockKey(key));
        }
    }

    private String countKey(String key) {
        return "login:fail:" + key;
    }

    private String lockKey(String key) {
        return "login:lock:" + key;
    }

    /** 存储抽象：生产用 Redis，测试用内存实现 */
    public interface Store {

        /** 自增并在首次写入时设置过期时间，返回自增后的值 */
        long increment(String key, Duration ttl);

        /** 写入值并设置过期时间 */
        void set(String key, String value, Duration ttl);

        /** 读取剩余存活秒数；不存在返回 null 或 <=0 */
        Long ttl(String key);

        /** 删除 key */
        void delete(String key);
    }

    /**
     * 内存实现：仅用于单元测试与单机兜底。
     *
     * <p><b>注意</b>：进程内存不共享，多实例部署下每个实例各算各的，
     * 生产必须使用 Redis 实现。
     */
    public static class InMemoryStore implements Store {

        private record Entry(String value, long expireAt) {
        }

        private final Map<String, Entry> map = new ConcurrentHashMap<>();

        @Override
        public long increment(String key, Duration ttl) {
            long now = System.currentTimeMillis();
            Entry entry = map.compute(key, (k, old) -> {
                if (old == null || old.expireAt() <= now) {
                    return new Entry("1", now + ttl.toMillis());
                }
                long next = Long.parseLong(old.value()) + 1;
                return new Entry(String.valueOf(next), old.expireAt());
            });
            return Long.parseLong(entry.value());
        }

        @Override
        public void set(String key, String value, Duration ttl) {
            map.put(key, new Entry(value, System.currentTimeMillis() + ttl.toMillis()));
        }

        @Override
        public Long ttl(String key) {
            Entry entry = map.get(key);
            if (entry == null) {
                return null;
            }
            long remain = (entry.expireAt() - System.currentTimeMillis()) / 1000;
            return remain > 0 ? remain : null;
        }

        @Override
        public void delete(String key) {
            map.remove(key);
        }
    }
}
