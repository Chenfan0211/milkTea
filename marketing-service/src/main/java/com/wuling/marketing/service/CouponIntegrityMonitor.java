package com.wuling.marketing.service;

import com.wuling.common.alert.AlertChannel;
import com.wuling.marketing.mapper.CouponIntegrityMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 优惠券数据完整性巡检（防复发）。
 *
 * <p>背景（2026-10-15）：小程序券包「已使用」里出现一批没有订单的券 ——
 * 应用代码写不出这种数据，是历史演示/批量造数<b>直写 user_coupon</b> 留下的，
 * 用户先发现、我们后排查。本巡检把这两条硬不变量做成定时检查，
 * 出了问题<b>主动告警</b>（复用 {@link AlertChannel}，落到 alerts.log 与 ALERT_PENDING，
 * 由运维脚本 scripts/alert-check.sh 以 exit code 暴露给外部监控），
 * 而不是等用户反馈。
 *
 * <p>巡检三条不变量（期望值都为 0）：
 * <ol>
 *   <li><b>已使用但没有订单号</b> —— 脏数据 / 直写库（CRITICAL）；</li>
 *   <li><b>同用户同模板多张有效持有券</b> —— 违背 uk_user_coupon_active，并发会重复发券（CRITICAL）；</li>
 *   <li><b>非已使用状态却带核销时间</b> —— use_time 只在 status='USED' 时有意义，
 *       旁路写入或旧数据会留下矛盾字段（WARNING）。</li>
 * </ol>
 *
 * <p><b>只告警、不自动改数</b>：脏数据要人来判断来源（本次就是造数），
 * 自动改数可能把真实业务数据改坏。明细与归位脚本见
 * {@code scripts/check-coupon-integrity.sql} 与 {@code scripts/fix-coupon-fake-used.sql}。
 *
 * <p>告警去重：连续多轮同一问题只告警一次，恢复时发 INFO 便于确认（与 OutboxMonitor 同款）。
 */
@Component
public class CouponIntegrityMonitor {

    private static final Logger log = LoggerFactory.getLogger(CouponIntegrityMonitor.class);

    private final CouponIntegrityMapper mapper;
    private final AlertChannel alertChannel;
    private final boolean enabled;

    private boolean usedWithoutOrderAlerting;
    private boolean duplicateActiveAlerting;
    private boolean nonUsedWithUseTimeAlerting;

    public CouponIntegrityMonitor(CouponIntegrityMapper mapper,
                                  AlertChannel alertChannel,
                                  @Value("${app.coupon-integrity-check.enabled:true}") boolean enabled) {
        this.mapper = mapper;
        this.alertChannel = alertChannel;
        this.enabled = enabled;
    }

    /** 每 10 分钟巡检一次（与 DLQ / outbox 监控同频，避免刷告警）。 */
    @Scheduled(cron = "${app.coupon-integrity-check.cron:0 */10 * * * ?}")
    public void check() {
        if (!enabled) {
            return;
        }
        try {
            int usedWithoutOrder = mapper.countUsedWithoutOrder();
            if (usedWithoutOrder > 0) {
                if (!usedWithoutOrderAlerting) {
                    alertChannel.send(AlertChannel.Level.CRITICAL,
                            "优惠券存在「已使用但没有订单号」的脏数据",
                            "共 " + usedWithoutOrder + " 张。正常核销必然带 lock_order_id，"
                                    + "此类数据只可能来自直写库（造数/脚本）。"
                                    + "排查明细执行 scripts/check-coupon-integrity.sql，"
                                    + "按有效期归位执行 scripts/fix-coupon-fake-used.sql（含备份与回滚）",
                            "coupon:USED_WITHOUT_ORDER");
                }
                usedWithoutOrderAlerting = true;
            } else if (usedWithoutOrderAlerting) {
                alertChannel.send(AlertChannel.Level.INFO,
                        "优惠券「已使用但没有订单号」的脏数据已清空",
                        "该巡检项恢复为 0", "coupon:USED_WITHOUT_ORDER");
                usedWithoutOrderAlerting = false;
            }

            int duplicateActive = mapper.countDuplicateActiveCoupons();
            if (duplicateActive > 0) {
                if (!duplicateActiveAlerting) {
                    alertChannel.send(AlertChannel.Level.CRITICAL,
                            "优惠券存在同用户同模板的多张有效持有券",
                            "共 " + duplicateActive + " 组。唯一键 uk_user_coupon_active"
                                    + "(user_id, active_coupon_id) 应当拦住，出现即说明索引缺失或被删，"
                                    + "并发领券会重复发券；请核对 information_schema.statistics 后补索引",
                            "coupon:DUPLICATE_ACTIVE");
                }
                duplicateActiveAlerting = true;
            } else if (duplicateActiveAlerting) {
                alertChannel.send(AlertChannel.Level.INFO,
                        "优惠券同用户同模板重复持有已恢复正常",
                        "该巡检项恢复为 0", "coupon:DUPLICATE_ACTIVE");
                duplicateActiveAlerting = false;
            }

            int nonUsedWithUseTime = mapper.countNonUsedWithUseTime();
            if (nonUsedWithUseTime > 0) {
                if (!nonUsedWithUseTimeAlerting) {
                    alertChannel.send(AlertChannel.Level.WARNING,
                            "优惠券存在「非已使用却带核销时间」的数据",
                            "共 " + nonUsedWithUseTime + " 张。use_time 只在 status=USED 时有意义："
                                    + "核销时写入、退款回退（restoreAfterRefund）清空；"
                                    + "出现即说明有旁路写入或历史脏数据，"
                                    + "用 scripts/check-coupon-integrity.sql 查看明细",
                            "coupon:NON_USED_WITH_USE_TIME");
                }
                nonUsedWithUseTimeAlerting = true;
            } else if (nonUsedWithUseTimeAlerting) {
                alertChannel.send(AlertChannel.Level.INFO,
                        "优惠券「非已使用却带核销时间」的数据已清空",
                        "该巡检项恢复为 0", "coupon:NON_USED_WITH_USE_TIME");
                nonUsedWithUseTimeAlerting = false;
            }
        } catch (Exception e) {
            // 巡检失败不影响业务（也绝不抛给调度器，避免后续轮次被中断）
            log.warn("优惠券数据完整性巡检执行失败: {}", e.getMessage());
        }
    }
}