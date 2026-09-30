package com.wuling.trade.pay.payout;

import com.wechat.pay.java.service.transferbatch.TransferBatchService;
import com.wechat.pay.java.service.transferbatch.model.InitiateBatchTransferRequest;
import com.wechat.pay.java.service.transferbatch.model.InitiateBatchTransferResponse;
import com.wechat.pay.java.service.transferbatch.model.TransferDetailInput;
import com.wuling.trade.pay.wxpay.WxPayProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 微信转账实现（WxPayoutGateway）单元测试。
 *
 * <p>用 mock 的 {@link TransferBatchService} 验证胶水代码：
 * 请求字段组装、响应解析、超限拒单、openid 缺失、SDK 异常兜底。
 * 不造 HTTP 层 —— 微信官方 SDK 的签名/URL 行为是 SDK 职责，不在本项目验证范围。
 */
class WxPayoutGatewayTest {

    private final TransferBatchService transferBatchService = mock(TransferBatchService.class);
    private final WxPayProperties properties = new WxPayProperties();

    private WxPayoutGateway gateway() {
        properties.setAppId("wx-test-app");
        properties.setPayoutNotifyUrl("https://api.example.com/api/v1/app/payout/notify");
        return new WxPayoutGateway(transferBatchService, properties);
    }

    @Test
    void transferAssemblesRequestAndParsesBatchId() {
        InitiateBatchTransferResponse response = new InitiateBatchTransferResponse();
        response.setBatchId("WX-BATCH-123");
        response.setBatchStatus("ACCEPTED");
        when(transferBatchService.initiateBatchTransfer(any())).thenReturn(response);

        PayoutGateway.PayoutResult result = gateway()
                .transfer("WD202609300001", 500L, "openid-abc", "五零时光提现");

        assertTrue(result.accepted());
        assertEquals("WX-BATCH-123", result.batchNo());
        assertTrue(result.failReason() == null || result.failReason().isEmpty());

        // 校验请求字段组装正确
        ArgumentCaptor<InitiateBatchTransferRequest> captor =
                ArgumentCaptor.forClass(InitiateBatchTransferRequest.class);
        verify(transferBatchService).initiateBatchTransfer(captor.capture());
        InitiateBatchTransferRequest req = captor.getValue();
        assertEquals("wx-test-app", req.getAppid());
        assertEquals("BWD202609300001", req.getOutBatchNo());
        assertEquals(Long.valueOf(500L), req.getTotalAmount());
        assertEquals(Integer.valueOf(1), req.getTotalNum());
        assertEquals("https://api.example.com/api/v1/app/payout/notify", req.getNotifyUrl());

        List<TransferDetailInput> details = req.getTransferDetailList();
        assertEquals(1, details.size());
        assertEquals("WD202609300001", details.get(0).getOutDetailNo());
        assertEquals(Long.valueOf(500L), details.get(0).getTransferAmount());
        assertEquals("openid-abc", details.get(0).getOpenid());
    }

    @Test
    void transferRejectsAmountOverLimit() {
        // 2000 元 = 200000 分；超出即拒单，不发起 SDK 调用
        PayoutGateway.PayoutResult result = gateway()
                .transfer("WD1", 200001L, "openid-abc", "五零时光提现");

        assertFalse(result.accepted());
        assertTrue(result.failReason().contains("2000"));
        // 未调 SDK
        verify(transferBatchService, org.mockito.Mockito.never())
                .initiateBatchTransfer(any());
    }

    @Test
    void transferRejectsMissingOpenid() {
        PayoutGateway.PayoutResult result = gateway()
                .transfer("WD1", 100L, null, "五零时光提现");

        assertFalse(result.accepted());
        assertTrue(result.failReason().contains("openid"));
        verify(transferBatchService, org.mockito.Mockito.never())
                .initiateBatchTransfer(any());
    }

    @Test
    void transferCatchesSdkException() {
        when(transferBatchService.initiateBatchTransfer(any()))
                .thenThrow(new RuntimeException("网络超时"));

        PayoutGateway.PayoutResult result = gateway()
                .transfer("WD1", 100L, "openid-abc", "五零时光提现");

        assertFalse(result.accepted());
        assertTrue(result.failReason().contains("网络超时"));
    }

    @Test
    void queryReturnsDetailStatus() {
        com.wechat.pay.java.service.transferbatch.model.TransferDetailEntity entity =
                new com.wechat.pay.java.service.transferbatch.model.TransferDetailEntity();
        entity.setDetailStatus("SUCCESS");
        when(transferBatchService.getTransferDetailByOutNo(any())).thenReturn(entity);

        PayoutGateway.PayoutQueryResult result = gateway().query("WD1");

        assertNotNull(result);
        assertEquals("SUCCESS", result.status());
    }
}
