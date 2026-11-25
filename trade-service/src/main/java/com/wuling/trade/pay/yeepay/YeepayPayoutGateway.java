package com.wuling.trade.pay.yeepay;

import com.wuling.trade.pay.payout.PayoutGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 易宝出款通道：分账 + 自助结算。
 *
 * <p><b>与微信通道的关键差异</b>：微信直接打款到零钱，一步完成；
 * 易宝要两步——先把钱分账到子商户（在 {@code 订单核销时} 已完成），
 * 再把子商户待结算余额结算到结算卡（本类负责的第二步）。
 *
 * <p>因此本类的 {@code transfer} 语义是「发起结算」，
 * 分账不在这里做（若在此做，一次提现要串行分账几十笔，耗时数分钟）。
 *
 * <p><b>异步</b>：{@code isImmediate=false}，结算回调确认后才置 PAID。
 */
@Component
@ConditionalOnProperty(name = "app.pay.yeepay.channel", havingValue = "yeepay")
public class YeepayPayoutGateway implements PayoutGateway {

    private static final Logger log = LoggerFactory.getLogger(YeepayPayoutGateway.class);

    private final YeepaySettleService settleService;

    public YeepayPayoutGateway(YeepaySettleService settleService) {
        this.settleService = settleService;
    }

    @Override
    public String channel() {
        return "YEEPAY";
    }

    @Override
    public PayoutResult transfer(String outTradeNo, long amountFen, PayeeRef payee, String remark) {
        if (payee == null || payee.yeepayMerchantNo() == null || payee.yeepayMerchantNo().isBlank()) {
            return new PayoutResult(false, null, "易宝子商户编号缺失，请先完成入驻");
        }
        if (amountFen <= 0) {
            return new PayoutResult(false, null, "提现金额必须大于 0");
        }
        try {
            YeepaySettleService.SettleResult result =
                    settleService.selfSettle(payee.yeepayMerchantNo(), outTradeNo);
            if (!result.accepted()) {
                return new PayoutResult(false, null, result.failReason());
            }
            // 受理成功：返回易宝结算流水号，等结算回调确认
            return new PayoutResult(true, result.yeepayFlowNo(), null);
        } catch (Exception e) {
            log.error("易宝结算受理失败 outTradeNo={} err={}", outTradeNo, e.getMessage());
            return new PayoutResult(false, null, "易宝结算受理失败：" + e.getMessage());
        }
    }

    @Override
    public PayoutQueryResult query(String outTradeNo, String yeepayMerchantNo) {
        // 易宝查询结算记录必须同时提供 merchantNo 与 settleRequestNo（= 提现单号）。
        // 缺 merchantNo 时无法查询，返回 null 让调用方保持 PROCESSING 等人工处理，
        // 而不是伪造一个状态——伪造状态会导致资金账实不符。
        if (yeepayMerchantNo == null || yeepayMerchantNo.isBlank()) {
            log.warn("易宝提现查询缺少 merchantNo，无法查单 outTradeNo={}", outTradeNo);
            return null;
        }
        try {
            YeepaySettleService.SettleQueryResult result =
                    settleService.queryRecords(yeepayMerchantNo, outTradeNo);
            if (result == null) {
                return null;
            }
            return new PayoutQueryResult(result.status(), result.message());
        } catch (Exception e) {
            log.warn("易宝提现查询异常 outTradeNo={} err={}", outTradeNo, e.getMessage());
            return null;
        }
    }
}
