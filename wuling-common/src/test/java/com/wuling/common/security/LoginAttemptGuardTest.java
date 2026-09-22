package com.wuling.common.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 登录失败锁定策略测试 */
class LoginAttemptGuardTest {

    @Test
    void shouldNotLockBelowThreshold() {
        LoginAttemptGuard guard = new LoginAttemptGuard(new LoginAttemptGuard.InMemoryStore());
        for (int i = 1; i < LoginAttemptGuard.MAX_FAILURES; i++) {
            assertFalse(guard.recordFailure("admin"), "第 " + i + " 次失败不应锁定");
            assertEquals(0, guard.lockedSeconds("admin"));
        }
    }

    @Test
    void shouldLockAtThreshold() {
        LoginAttemptGuard guard = new LoginAttemptGuard(new LoginAttemptGuard.InMemoryStore());
        boolean locked = false;
        for (int i = 0; i < LoginAttemptGuard.MAX_FAILURES; i++) {
            locked = guard.recordFailure("admin");
        }
        assertTrue(locked, "达到阈值应锁定");
        assertTrue(guard.lockedSeconds("admin") > 0, "应处于锁定状态");
    }

    @Test
    void successShouldClearCounterAndLock() {
        LoginAttemptGuard guard = new LoginAttemptGuard(new LoginAttemptGuard.InMemoryStore());
        for (int i = 0; i < LoginAttemptGuard.MAX_FAILURES; i++) {
            guard.recordFailure("admin");
        }
        assertTrue(guard.lockedSeconds("admin") > 0);

        guard.recordSuccess("admin");
        assertEquals(0, guard.lockedSeconds("admin"), "成功后应解除锁定");
        // 再次失败一次不应立刻锁定（计数已清零）
        assertFalse(guard.recordFailure("admin"));
    }

    @Test
    void lockShouldBePerKeyNotGlobal() {
        LoginAttemptGuard guard = new LoginAttemptGuard(new LoginAttemptGuard.InMemoryStore());
        for (int i = 0; i < LoginAttemptGuard.MAX_FAILURES; i++) {
            guard.recordFailure("admin");
        }
        // 另一个用户名不受影响
        assertFalse(guard.recordFailure("operator"), "不同用户名的计数应独立");
        assertEquals(0, guard.lockedSeconds("operator"));
    }

    @Test
    void multipleKeysShouldLockIndependently() {
        LoginAttemptGuard guard = new LoginAttemptGuard(new LoginAttemptGuard.InMemoryStore());
        // 按「用户名 + IP」双维度记录，任一维度达阈值即锁定
        for (int i = 0; i < LoginAttemptGuard.MAX_FAILURES; i++) {
            guard.recordFailure("admin", "10.0.0.1");
        }
        assertTrue(guard.lockedSeconds("admin", "10.0.0.1") > 0);
        // 仅用户名维度也已锁定
        assertTrue(guard.lockedSeconds("admin") > 0);
    }

    @Test
    void inMemoryStoreShouldExpire() throws Exception {
        LoginAttemptGuard.InMemoryStore store = new LoginAttemptGuard.InMemoryStore();
        // 用秒级 TTL，避免毫秒被整除为 0 影响断言
        store.set("k", "1", Duration.ofSeconds(2));
        assertEquals(2, store.ttl("k"));
        Thread.sleep(2100);
        assertEquals(null, store.ttl("k"), "过期后 ttl 应为空");
    }
}
