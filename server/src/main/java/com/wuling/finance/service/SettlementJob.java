package com.wuling.finance.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** 每日 T+1 结算任务 */
@Component
public class SettlementJob {

    private static final Logger log = LoggerFactory.getLogger(SettlementJob.class);

    private final LedgerService ledgerService;

    public SettlementJob(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    /** 每天 01:00 把前一日的待结算转为可结算 */
    @Scheduled(cron = "0 0 1 * * ?")
    public void settleDaily() {
        int count = ledgerService.settleDue(LocalDate.now().minusDays(1));
        log.info("daily settlement done, records={}", count);
    }
}
