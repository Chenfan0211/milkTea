package com.wuling.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.trade.entity.BalancePayIntent;
import com.wuling.trade.entity.Order;
import com.wuling.trade.entity.Payment;
import com.wuling.trade.mapper.BalancePayIntentMapper;
import com.wuling.trade.mapper.OrderMapper;
import com.wuling.trade.mapper.PaymentMapper;
import com.wuling.trade.pay.storedvalue.StoredValueBalancePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 余额支付「扣款-落库」补偿对账任务。
 *
 * <p><b>它解决什么</b>：余额支付在 trade 域一次事务里
 * 「调 marketing 扣余额 → 置订单已支付 → 写支付单」。扣余额发生在 marketing 库
 * （远程、独立事务、立即提交），订单落库在 trade 库。若 trade 本地事务在
 * 扣款成功后回滚，就会留下「已扣款但订单未支付」的悬挂单 —— 用户钱被扣了，
 * 订单却还是待支付，且无支付记录。
 *
 * <p><b>判定与处理（只处理资金，不改订单状态）</b>：
 * <ol>
 *   <li>扫 {@code balance_pay_intent} 中状态为 PENDING 的记录；</li>
 *   <li>若订单已到 PAID/COMPLETED 且有 PAID 支付单 → 意图标记 DONE（正常，补漏）；</li>
 *   <li>否则（订单仍未支付，或支付单缺失）→ 视为悬挂单，调用 marketing
 *       退回余额，并把意图标记 COMPENSATED。</li>
 * </ol>
 *
 * <p><b>为什么只退回余额、不自动把订单置为已支付</b>：资金与订单状态一旦错位，
 * 保守做法是「退钱」而不是「猜订单该不该付」—— 把钱退回用户，让用户重新支付，
 * 风险远低于「凭空把一笔未确认的扣款当成已支付交付商品」。
 *
 * <p><b>幂等与并发</b>：回冲前用条件更新（PENDING → COMPENSATED）作为闸门，
 * 且 marketing 的 addBalance 有非负约束，重复回冲会被余额上限/订单状态挡住。
 * 频率可用 {@code app.balance-pay-reconcile.cron} 覆盖，默认每 5 分钟一次
 * （悬挂单窗口应尽量短）。
 */
@Component
public class BalancePayReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(BalancePayReconcileJob.class);

    private final BalancePayIntentMapper intentMapper;
    private final OrderMapper orderMapper;
    private final PaymentMapper paymentMapper;
    private final StoredValueBalancePort storedValueBalancePort;
    private final boolean enabled;

    public BalancePayReconcileJob(BalancePayIntentMapper intentMapper,
                                  OrderMapper orderMapper,
                                  PaymentMapper paymentMapper,
                                  StoredValueBalancePort storedValueBalancePort,
                                  @Value("${app.balance-pay-reconcile.enabled:true}") boolean enabled) {
        this.intentMapper = intentMapper;
        this.orderMapper = orderMapper;
        this.paymentMapper = paymentMapper;
        this.storedValueBalancePort = storedValueBalancePort;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${app.balance-pay-reconcile.cron:0 */5 * * * ?}")
    public void reconcile() {
        if (!enabled) {
            return;
        }
        try {
            List<BalancePayIntent> pendings = intentMapper.selectList(
                    new LambdaQueryWrapper<BalancePayIntent>()
                            .eq(BalancePayIntent::getStatus, BalancePayIntent.STATUS_PENDING));
            if (pendings.isEmpty()) {
                return;
            }
            int done = 0;
            int compensated = 0;
            for (BalancePayIntent intent : pendings) {
                // 只有「较老」的意图才处理：给正常流程留出提交时间，
                // 避免主事务尚未提交就被误判为悬挂单而回冲（那会造成双重扣款）。
                if (intent.getCreateTime() != null
                        && intent.getCreateTime().isAfter(LocalDateTime.now().minusMinutes(2))) {
                    continue;
                }
                if (isOrderPaid(intent)) {
                    intentMapper.update(updateStatus(intent.getId(), BalancePayIntent.STATUS_DONE),
                            new LambdaQueryWrapper<BalancePayIntent>()
                                    .eq(BalancePayIntent::getId, intent.getId())
                                    .eq(BalancePayIntent::getStatus, BalancePayIntent.STATUS_PENDING));
                    done++;
                    continue;
                }
                // 悬挂单：退回余额并标记已回冲
                try {
                    storedValueBalancePort.refund(intent.getUserId(), intent.getAmount(), intent.getOrderNo());
                    intentMapper.update(updateStatus(intent.getId(), BalancePayIntent.STATUS_COMPENSATED),
                            new LambdaQueryWrapper<BalancePayIntent>()
                                    .eq(BalancePayIntent::getId, intent.getId())
                                    .eq(BalancePayIntent::getStatus, BalancePayIntent.STATUS_PENDING));
                    compensated++;
                    log.warn("余额支付悬挂单已回冲 orderNo={} userId={} amount={}",
                            intent.getOrderNo(), intent.getUserId(), intent.getAmount());
                } catch (Exception e) {
                    // 回冲失败保留 PENDING，下轮重试；记日志并告警，不中断整批
                    log.error("余额支付悬挂单回冲失败，保留 PENDING 下轮重试 orderNo={} err={}",
                            intent.getOrderNo(), e.getMessage());
                }
            }
            if (done > 0 || compensated > 0) {
                log.info("余额支付补偿完成 done={} compensated={}", done, compensated);
            }
        } catch (Exception e) {
            // 补偿任务失败不应影响其他定时任务
            log.error("余额支付补偿任务执行失败", e);
        }
    }

    /** 订单是否已支付且存在 PAID 的支付单（扣款-落库两段均成功） */
    private boolean isOrderPaid(BalancePayIntent intent) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, intent.getOrderNo())
                .last("limit 1"));
        if (order == null) {
            return false;
        }
        if (!OrderService.STATUS_PAID.equals(order.getStatus())
                && !OrderService.STATUS_COMPLETED.equals(order.getStatus())) {
            return false;
        }
        Long paidCount = paymentMapper.selectCount(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getOrderNo, intent.getOrderNo())
                .eq(Payment::getStandardStatus, "PAID"));
        return paidCount != null && paidCount > 0;
    }

    private BalancePayIntent updateStatus(Long id, String status) {
        BalancePayIntent patch = new BalancePayIntent();
        patch.setId(id);
        patch.setStatus(status);
        return patch;
    }
}