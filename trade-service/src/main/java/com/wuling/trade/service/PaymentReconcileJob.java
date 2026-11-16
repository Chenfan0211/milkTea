package com.wuling.trade.service;

import com.wuling.trade.mapper.PaymentMapper;
import com.wuling.trade.entity.Payment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 商城微信支付通知丢失的主动查单补偿入口。 */
@Component
public class PaymentReconcileJob {
    private static final Logger log = LoggerFactory.getLogger(PaymentReconcileJob.class);
    private final PaymentMapper mapper;
    private final PaymentService service;
    private final boolean enabled;
    private final int batchSize;
    private long afterId;

    public PaymentReconcileJob(PaymentMapper mapper, PaymentService service,
                               @Value("${app.pay.reconcile-enabled:true}") boolean enabled,
                               @Value("${app.pay.reconcile-batch-size:200}") int batchSize) {
        this.mapper = mapper; this.service = service; this.enabled = enabled; this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.pay.reconcile-delay-ms:30000}")
    public void run() { sweep(); }

    public synchronized int sweep() {
        if (!enabled) return 0;
        java.util.List<Payment> batch = mapper.selectPayingBatch(afterId, Math.max(1, batchSize));
        if (batch.isEmpty() && afterId != 0) {
            afterId = 0;
            batch = mapper.selectPayingBatch(0, Math.max(1, batchSize));
        }
        int settled = 0;
        for (Payment payment : batch) {
            afterId = payment.getId();
            try {
                Payment result = service.reconcilePayment(payment.getId());
                if (result != null && "PAID".equals(result.getStandardStatus())) settled++;
            } catch (Exception error) {
                log.warn("支付查单补偿暂未完成 paymentId={} orderNo={}",
                        payment.getId(), payment.getOrderNo());
            }
        }
        return settled;
    }
}
