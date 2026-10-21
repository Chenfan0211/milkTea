package com.wuling.marketing.service;

import com.wuling.common.alert.AlertChannel;
import com.wuling.marketing.mapper.CouponIntegrityMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 优惠券数据完整性巡检测试（防复发）。
 *
 * <p>背景：2026-10-15 「已使用」里出现没有订单的券，是直写库造数留下的脏数据；
 * 本巡检把两条硬不变量做成定时检查 + 主动告警，避免再次「用户先发现、我们后排查」。
 *
 * <p>守四件事：健康不告警 / 异常告警一次（去重）/ 恢复发 INFO / 关闭与异常都不影响业务。
 */
class CouponIntegrityMonitorTest {

    /** 记录型告警通道（与 DlqMonitorTest 同款桩） */
    static class RecordingChannel implements AlertChannel {
        final List<String> alerts = new ArrayList<>();

        @Override
        public void send(Level level, String title, String detail, String bizKey) {
            alerts.add(level + "|" + title + "|" + bizKey);
        }

        @Override
        public String channelName() {
            return "recording";
        }
    }

    private final CouponIntegrityMapper mapper = mock(CouponIntegrityMapper.class);
    private final RecordingChannel channel = new RecordingChannel();

    private CouponIntegrityMonitor monitor(boolean enabled) {
        return new CouponIntegrityMonitor(mapper, channel, enabled);
    }

    @Test
    @DisplayName("两条不变量都为 0 时不得告警")
    void noAlertWhenHealthy() {
        monitor(true).check();

        assertTrue(channel.alerts.isEmpty(), "健康状态下不得产生任何告警");
    }

    @Test
    @DisplayName("「已使用但没有订单号」→ CRITICAL，且连续多轮只告警一次（去重）")
    void alertsUsedWithoutOrderOnce() {
        when(mapper.countUsedWithoutOrder()).thenReturn(3);

        CouponIntegrityMonitor m = monitor(true);
        m.check();
        m.check();
        m.check();

        assertEquals(1, channel.alerts.size(), "连续多轮只应告警一次");
        assertTrue(channel.alerts.get(0).contains("CRITICAL"), "脏数据应为 CRITICAL");
        assertTrue(channel.alerts.get(0).contains("coupon:USED_WITHOUT_ORDER"), "应带业务键便于检索");
    }

    @Test
    @DisplayName("脏数据清空后发 INFO，便于确认恢复")
    void notifiesRecoveryWhenCleaned() {
        when(mapper.countUsedWithoutOrder()).thenReturn(2);
        CouponIntegrityMonitor m = monitor(true);
        m.check();

        when(mapper.countUsedWithoutOrder()).thenReturn(0);
        m.check();

        assertEquals(2, channel.alerts.size());
        assertTrue(channel.alerts.get(1).contains("INFO"), "恢复应发 INFO");
        assertTrue(channel.alerts.get(1).contains("已清空"), "标题应说明已恢复");
    }

    @Test
    @DisplayName("同用户同模板多张有效持有券 → CRITICAL（唯一键不变量被破坏）")
    void alertsDuplicateActiveCoupons() {
        when(mapper.countDuplicateActiveCoupons()).thenReturn(2);

        monitor(true).check();

        assertEquals(1, channel.alerts.size());
        assertTrue(channel.alerts.get(0).contains("CRITICAL"));
        assertTrue(channel.alerts.get(0).contains("coupon:DUPLICATE_ACTIVE"));
    }

    @Test
    @DisplayName("「非已使用却带核销时间」→ WARNING（状态与字段自相矛盾）")
    void alertsNonUsedWithUseTime() {
        when(mapper.countNonUsedWithUseTime()).thenReturn(4);

        monitor(true).check();

        assertEquals(1, channel.alerts.size());
        assertTrue(channel.alerts.get(0).contains("WARNING"), "字段矛盾应为 WARNING");
        assertTrue(channel.alerts.get(0).contains("coupon:NON_USED_WITH_USE_TIME"));
    }

    @Test
    @DisplayName("「非已使用却带核销时间」清空后发 INFO")
    void notifiesRecoveryWhenUseTimeCleaned() {
        when(mapper.countNonUsedWithUseTime()).thenReturn(1);
        CouponIntegrityMonitor m = monitor(true);
        m.check();

        when(mapper.countNonUsedWithUseTime()).thenReturn(0);
        m.check();

        assertEquals(2, channel.alerts.size());
        assertTrue(channel.alerts.get(1).contains("INFO"), "恢复应发 INFO");
    }    @Test
    @DisplayName("开关关闭时不查询、不告警")
    void skipsWhenDisabled() {
        monitor(false).check();

        verify(mapper, never()).countUsedWithoutOrder();
        verify(mapper, never()).countDuplicateActiveCoupons();
        assertTrue(channel.alerts.isEmpty());
    }

    @Test
    @DisplayName("巡检异常不得抛出（不影响业务，也不中断后续轮次）")
    void swallowsFailure() {
        when(mapper.countUsedWithoutOrder()).thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> monitor(true).check());
    }
}