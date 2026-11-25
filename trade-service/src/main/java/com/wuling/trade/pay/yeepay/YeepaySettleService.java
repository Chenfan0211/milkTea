package com.wuling.trade.pay.yeepay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeepay.yop.sdk.service.common.response.YopResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 易宝自助结算（{@code /rest/v1.0/settle/self-settle/apply}）。
 *
 * <p><b>这是提现的最后一步</b>：把经营者子商户「待结算账户」的钱结算到其结算卡。
 *
 * <p><b>为什么必须是「自助结算」</b>：若子商户配成 T1 结算，分账到账后次日
 * 系统会自动打款到卡，<b>绕过平台运营审核</b>，「审核通过才出款」直接失效。
 * 这一项属于易宝侧配置，代码无法兜底，必须写进商务确认单。
 *
 * <p><b>异步语义</b>：接口返回 {@code code=000000} 只代表「已受理」，
 * 实际结果由「结算结果通知」回调或 {@code settle/records/query} 查询确认。
 */
@Service
@ConditionalOnProperty(name = "app.pay.yeepay.channel", havingValue = "yeepay")
public class YeepaySettleService {

    private static final Logger log = LoggerFactory.getLogger(YeepaySettleService.class);

    private static final String PATH_APPLY = "/rest/v1.0/settle/self-settle/apply";
    private static final String PATH_RECORDS = "/rest/v1.0/settle/records/query";
    private static final String PATH_BALANCE = "/rest/v1.0/settle/balance/query";

    private final YeepayApiClient apiClient;
    private final YeepayProperties properties;
    private final ObjectMapper objectMapper;

    public YeepaySettleService(YeepayApiClient apiClient,
                               YeepayProperties properties,
                               ObjectMapper objectMapper) {
        this.apiClient = apiClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 申请结算当前全部可结算金额。
     *
     * @param merchantNo      经营者子商户编号
     * @param settleRequestNo 结算请求号（唯一，幂等键，本项目用提现单号）
     */
    public SettleResult selfSettle(String merchantNo, String settleRequestNo) {
        Map<String, String> params = new HashMap<>();
        params.put("parentMerchantNo", properties.getMerchantNo());
        params.put("merchantNo", merchantNo);
        params.put("settleRequestNo", settleRequestNo);
        params.put("operatePeriod", "ALL");
        params.put("notifyUrl", properties.notifyUrl("/api/v1/app/yeepay/notify/settle"));
        try {
            YopResponse response = apiClient.post(PATH_APPLY, params);
            JsonNode root = parse(response);
            String code = text(root, "code");
            if (!"000000".equals(code)) {
                String message = text(root, "message");
                log.warn("易宝结算未受理 merchantNo={} requestNo={} code={} msg={}",
                        merchantNo, settleRequestNo, code, message);
                return new SettleResult(false, null, null,
                        message == null ? "结算失败（" + code + "）" : message);
            }
            return new SettleResult(true, text(root, "yeepayFlowNo"), "PROCESSING", null);
        } catch (Exception e) {
            log.error("易宝结算调用异常 merchantNo={} requestNo={} err={}",
                    merchantNo, settleRequestNo, e.getMessage());
            return new SettleResult(false, null, null, "结算请求异常：" + e.getMessage());
        }
    }

    /** 查询结算记录（补偿回调丢失）。 */
    public SettleQueryResult queryRecords(String merchantNo, String settleRequestNo) {
        Map<String, String> params = new HashMap<>();
        params.put("parentMerchantNo", properties.getMerchantNo());
        params.put("merchantNo", merchantNo);
        params.put("settleRequestNo", settleRequestNo);
        try {
            YopResponse response = apiClient.get(PATH_RECORDS, params);
            JsonNode root = parse(response);
            return new SettleQueryResult(text(root, "status"), text(root, "message"));
        } catch (Exception e) {
            log.warn("查询易宝结算记录失败 merchantNo={} requestNo={} err={}",
                    merchantNo, settleRequestNo, e.getMessage());
            return null;
        }
    }

    /** 查询待结算金额（对账用，不参与资金逻辑）。 */
    public long querySettlableAmountFen(String merchantNo) {
        Map<String, String> params = new HashMap<>();
        params.put("parentMerchantNo", properties.getMerchantNo());
        params.put("merchantNo", merchantNo);
        params.put("operatePeriod", "ALL");
        try {
            YopResponse response = apiClient.get(PATH_BALANCE, params);
            JsonNode root = parse(response);
            String amount = text(root, "settlableAmount");
            if (amount == null) {
                return 0L;
            }
            // 易宝返回元（两位小数），转为分
            return Math.round(Double.parseDouble(amount) * 100);
        } catch (Exception e) {
            log.warn("查询易宝待结算金额失败 merchantNo={} err={}", merchantNo, e.getMessage());
            return 0L;
        }
    }

    private JsonNode parse(YopResponse response) throws Exception {
        Object result = response.getResult();
        if (result == null) {
            return objectMapper.createObjectNode();
        }
        if (result instanceof String text) {
            return objectMapper.readTree(text);
        }
        return objectMapper.valueToTree(result);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    /** 结算结果。 */
    public record SettleResult(boolean accepted, String yeepayFlowNo, String status, String failReason) {
    }

    /** 结算查询结果。 */
    public record SettleQueryResult(String status, String message) {
    }
}
