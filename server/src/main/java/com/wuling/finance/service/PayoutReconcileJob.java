package com.wuling.finance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.finance.entity.Withdrawal;
import com.wuling.finance.mapper.WithdrawalMapper;
import com.wuling.finance.port.PayoutPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 提现出款补偿任务。
 *
 * <p><b>为什么必须有它</b>：提现出款是异步的——审核通过后置 {@code PROCESSING}，
 * 等三方回调确认。但回调可能丢失（网络抖动、通知地址不可用、易宝重试耗尽），
 * 此时提现单会永久卡在 {@code PROCESSING}，钱既不到账也不解冻，用户只能投诉。
 * 本任务定时主动查单，把卡住的单子推向终态。
 *
 * <p><b>为什么不能超时直接置失败</b>：三方可能实际已打款成功，
 * 直接置 FAILED 并解冻会导致「钱出去了，账上还退给用户」的重复支付。
 * 因此只在<b>三方明确返回失败</b>时才置 FAILED；查不到就继续等。
 *
 * <p><b>超过阈值的人工介入</b>：超过 {@link #MANUAL_REVIEW_HOURS} 小时仍无法确认的，
 * 记 CRITICAL 日志让运营介入，绝不自动处置。
 */
@Component
public class PayoutReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(PayoutReconcileJob.class);

    /** 超过该时长仍无结果，转人工核查（不自动处置，避免重复付款）。 */
    private static final int MANUAL_REVIEW_HOURS = 24;

    /** 每批处理数量，避免一次拉太多拖慢任务。 */
    private static final int BATCH_SIZE = 100;

    private final WithdrawalMapper withdrawalMapper;
    private final PayoutPort payoutPort;
    private final WithdrawalService withdrawalService;
    /** 入驻记录查询（解析易宝子商户号）；未启用易宝时可为 null。 */
    private final com.wuling.subject.onboard.MerchantOnboardService merchantOnboardService;

    public PayoutReconcileJob(WithdrawalMapper withdrawalMapper,
                              PayoutPort payoutPort,
                              WithdrawalService withdrawalService,
                              org.springframework.beans.factory.ObjectProvider<
                                      com.wuling.subject.onboard.MerchantOnboardService> onboardProvider) {
        this.withdrawalMapper = withdrawalMapper;
        this.payoutPort = payoutPort;
        this.withdrawalService = withdrawalService;
        this.merchantOnboardService = onboardProvider.getIfAvailable();
    }

    /**
     * 每 5 分钟扫描一次 PROCESSING 提现单。
     *
     * <p>只处理「已发出超过 2 分钟」的单据：留出正常回调的窗口，
     * 避免刚发出的单就被误判。
     */
    @Scheduled(cron = "${app.payout-reconcile.cron:0 */5 * * * ?}")
    public void reconcileProcessing() {
        LocalDateTime graceLine = LocalDateTime.now().minusMinutes(2);
        List<Withdrawal> pending = withdrawalMapper.selectList(
                new LambdaQueryWrapper<Withdrawal>()
                        .eq(Withdrawal::getStatus, WithdrawalService.PROCESSING)
                        .lt(Withdrawal::getUpdateTime, graceLine)
                        .orderByAsc(Withdrawal::getUpdateTime)
                        .last("limit " + BATCH_SIZE));
        if (pending.isEmpty()) {
            return;
        }
        log.info("提现补偿任务启动，待核查 {} 笔", pending.size());

        for (Withdrawal withdrawal : pending) {
            try {
                reconcileOne(withdrawal);
            } catch (Exception e) {
                log.error("提现补偿单笔失败 withdrawNo={} err={}",
                        withdrawal.getWithdrawNo(), e.getMessage());
            }
        }
    }

    private void reconcileOne(Withdrawal withdrawal) {
        // 易宝通道查单需要子商户号：从入住记录/结算卡反查。
        // 微信通道该值为 null，由 RemotePayoutAdapter 自动忽略。
        String yeepayMerchantNo = resolveYeepayMerchantNo(withdrawal);
        PayoutPort.PayoutQueryResult result =
                payoutPort.query(withdrawal.getWithdrawNo(), yeepayMerchantNo);
        if (result == null) {
            // 查不到是补偿流程的正常分支：保持 PROCESSING 等下一轮
            checkManualReviewTimeout(withdrawal);
            return;
        }
        if (!result.supported()) {
            checkManualReviewTimeout(withdrawal);
            return;
        }
        String status = result.status();
        if (status == null) {
            checkManualReviewTimeout(withdrawal);
            return;
        }
        switch (status.toUpperCase()) {
            case "SUCCESS" -> {
                log.info("提现补偿确认成功 withdrawNo={}", withdrawal.getWithdrawNo());
                withdrawalService.confirmPaid(withdrawal.getWithdrawNo());
            }
            case "FAIL", "FAILED", "CLOSED" -> {
                log.info("提现补偿确认失败 withdrawNo={} reason={}",
                        withdrawal.getWithdrawNo(), result.failReason());
                withdrawalService.confirmFailed(withdrawal.getWithdrawNo(),
                        result.failReason() == null ? "出款失败（补偿确认）" : result.failReason());
            }
            case "PROCESSING", "REMITING", "REQUEST_ACCEPT", "REQUEST_RECEIVE" -> {
                // 仍在处理中：继续等
                checkManualReviewTimeout(withdrawal);
            }
            default -> checkManualReviewTimeout(withdrawal);
        }
    }

    /**
     * 解析该提现单对应的易宝子商户编号。
     *
     * <p>只对易宝通道有意义；微信/mock 返回 null。
     * 优先从入驻记录取（子商户号在入驻完成时写入），
     * 取不到则放弃查单——没有商户号易宝接口无法定位订单。
     */
    private String resolveYeepayMerchantNo(Withdrawal withdrawal) {
        if (!WithdrawalService.PAYOUT_CHANNEL_YEEPAY.equalsIgnoreCase(
                withdrawal.getPayoutChannel())) {
            return null;
        }
        try {
            return merchantOnboardService == null
                    ? null : merchantOnboardService.yeepayMerchantNo(withdrawal.getSubjectId());
        } catch (Exception e) {
            log.warn("解析易宝子商户号失败 withdrawNo={} err={}",
                    withdrawal.getWithdrawNo(), e.getMessage());
            return null;
        }
    }

    /**
     * 超时未确认的单据记 CRITICAL，交人工核查。
     *
     * <p>刻意<b>不</b>自动置失败：钱可能已经到账，自动解冻会造成重复支付。
     */
    private void checkManualReviewTimeout(Withdrawal withdrawal) {
        LocalDateTime threshold = LocalDateTime.now().minusHours(MANUAL_REVIEW_HOURS);
        if (withdrawal.getReviewTime() != null && withdrawal.getReviewTime().isBefore(threshold)) {
            log.error("提现单超过 {} 小时仍未确认，需人工核查 withdrawNo={} amount={} subjectId={} channel={}",
                    MANUAL_REVIEW_HOURS, withdrawal.getWithdrawNo(), withdrawal.getAmount(),
                    withdrawal.getSubjectId(), withdrawal.getPayoutChannel());
        }
    }
}
