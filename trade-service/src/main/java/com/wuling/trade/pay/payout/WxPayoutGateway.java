package com.wuling.trade.pay.payout;

import com.wechat.pay.java.service.transferbatch.TransferBatchService;
import com.wechat.pay.java.service.transferbatch.model.GetTransferBatchByOutNoRequest;
import com.wechat.pay.java.service.transferbatch.model.GetTransferDetailByOutNoRequest;
import com.wechat.pay.java.service.transferbatch.model.InitiateBatchTransferRequest;
import com.wechat.pay.java.service.transferbatch.model.InitiateBatchTransferResponse;
import com.wechat.pay.java.service.transferbatch.model.TransferBatchEntity;
import com.wechat.pay.java.service.transferbatch.model.TransferDetailInput;
import com.wechat.pay.java.service.transferbatch.model.TransferDetailEntity;
import com.wuling.trade.pay.wxpay.WxPayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 微信「商家转账到零钱」出款实现（第 16 期提现对接）。
 *
 * <p><b>条件装配</b>：仅当 {@code app.pay.wxpay.channel=wxpay} 时生效，
 * 与 {@code WxPaySdkConfig} 的证书装配隔离 —— mock 通道下本类不创建，
 * 不发任何微信请求，提现维持同步记账行为。
 *
 * <p><b>单笔限额（拒单策略，产品已确认）</b>：微信商家转账给单个用户单笔默认
 * 上限 2000 元（200000 分）。超出直接拒绝（{@code accepted=false + 原因}），
 * 由上游解冻并提示用户「单笔超限」，不做自动拆批。
 */
@Component
@ConditionalOnProperty(name = "app.pay.wxpay.channel", havingValue = "wxpay")
public class WxPayoutGateway implements PayoutGateway {

    private static final Logger log = LoggerFactory.getLogger(WxPayoutGateway.class);

    /** 微信商家转账单笔上限（分）：2000 元，以商户号签约额度为准 */
    private static final long MAX_TRANSFER_FEN = 200000L;

    private final TransferBatchService transferBatchService;
    private final WxPayProperties properties;

    public WxPayoutGateway(TransferBatchService transferBatchService,
                           WxPayProperties properties) {
        this.transferBatchService = transferBatchService;
        this.properties = properties;
    }

    @Override
    public String channel() {
        return "WXPAY";
    }

    @Override
    public PayoutResult transfer(String outDetailNo, long amountFen, String openid, String remark) {
        if (!StringUtils.hasText(openid)) {
            return new PayoutResult(false, null, "收款人 openid 缺失");
        }
        if (amountFen <= 0) {
            return new PayoutResult(false, null, "转账金额必须大于 0");
        }
        if (amountFen > MAX_TRANSFER_FEN) {
            return new PayoutResult(false, null,
                    "单笔提现超过 2000 元上限，请分批提现");
        }

        try {
            TransferDetailInput detail = new TransferDetailInput();
            detail.setOutDetailNo(outDetailNo);
            detail.setTransferAmount(amountFen);
            detail.setTransferRemark(remark == null ? "五零时光提现" : remark);
            detail.setOpenid(openid);

            InitiateBatchTransferRequest request = new InitiateBatchTransferRequest();
            request.setAppid(properties.getAppId());
            request.setOutBatchNo("B" + outDetailNo);
            request.setBatchName("五零时光提现");
            request.setBatchRemark("五零时光提现转账");
            request.setTotalAmount(amountFen);
            request.setTotalNum(1);
            request.setTransferDetailList(List.of(detail));
            if (StringUtils.hasText(properties.getPayoutNotifyUrl())) {
                request.setNotifyUrl(properties.getPayoutNotifyUrl());
            }

            InitiateBatchTransferResponse response = transferBatchService.initiateBatchTransfer(request);
            // 受理成功：batchId 为微信批次号
            return new PayoutResult(true, response.getBatchId(), null);
        } catch (Exception e) {
            log.error("微信转账受理失败 outDetailNo={} err={}", outDetailNo, e.getMessage());
            return new PayoutResult(false, null, "微信转账受理失败：" + e.getMessage());
        }
    }

    @Override
    public PayoutQueryResult query(String outDetailNo) {
        try {
            // 优先按商户转账单号查明细（单笔语义）
            GetTransferDetailByOutNoRequest detailRequest = new GetTransferDetailByOutNoRequest();
            detailRequest.setOutDetailNo(outDetailNo);
            detailRequest.setOutBatchNo("B" + outDetailNo);
            TransferDetailEntity detail = transferBatchService.getTransferDetailByOutNo(detailRequest);
            if (detail != null && detail.getDetailStatus() != null) {
                return new PayoutQueryResult(detail.getDetailStatus(), null);
            }
            return null;
        } catch (Exception e) {
            log.warn("微信转账查询失败 outDetailNo={} err={}", outDetailNo, e.getMessage());
            return null;
        }
    }
}
