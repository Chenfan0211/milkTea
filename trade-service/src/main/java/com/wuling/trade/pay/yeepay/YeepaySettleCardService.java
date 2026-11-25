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
 * 易宝结算卡（{@code /rest/v1.0/settle/settle-card/add}）。
 *
 * <p><b>结算卡 ≠ 提现卡</b>：订单分账路线下，钱进经营者的「子商户待结算账户」，
 * 只能通过自助结算打到<b>结算卡</b>。提现卡（{@code account/withdraw/card/bind}）
 * 是另一条产品线，需要另外开通。
 *
 * <p><b>绑定即校验</b>：易宝在绑卡时做卡户名一致性校验
 * （错误码 {@code UA40021 身份证号与银行账号不匹配}），
 * 因此不需要额外调用四要素认证接口（按次计费）。
 */
@Service
@ConditionalOnProperty(name = "app.pay.yeepay.channel", havingValue = "yeepay")
public class YeepaySettleCardService {

    private static final Logger log = LoggerFactory.getLogger(YeepaySettleCardService.class);

    private static final String PATH_BIND = "/rest/v1.0/settle/settle-card/add";
    private static final String PATH_QUERY = "/rest/v1.0/settle/settle-way/query";

    private final YeepayApiClient apiClient;
    private final ObjectMapper objectMapper;

    public YeepaySettleCardService(YeepayApiClient apiClient, ObjectMapper objectMapper) {
        this.apiClient = apiClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 绑定结算卡。
     *
     * @param merchantNo   易宝子商户编号
     * @param bankCardType DEBIT_CARD 借记卡 / ENTERPRISE_ACCOUNT 对公账户 / UNIT_SETTLEMENT_CARD 单位结算卡
     * @param accountNo    银行账号
     * @param bankCode     开户总行编码
     * @param branchCode   开户支行编码
     */
    public BindResult bind(String merchantNo, String bankCardType, String accountNo,
                           String bankCode, String branchCode) {
        Map<String, String> params = new HashMap<>();
        params.put("merchantNo", merchantNo);
        params.put("bankCardType", bankCardType);
        params.put("accountNo", accountNo);
        if (bankCode != null && !bankCode.isBlank()) {
            params.put("bankCode", bankCode);
        }
        if (branchCode != null && !branchCode.isBlank()) {
            params.put("branchCode", branchCode);
        }
        try {
            YopResponse response = apiClient.post(PATH_BIND, params);
            JsonNode root = parse(response);
            String returnCode = text(root, "returnCode");
            if ("UA00000".equals(returnCode)) {
                return new BindResult(true, text(root, "bindId"), null);
            }
            // UA40006 提现卡已存在：视为成功，但拿不到 bindId，需用查询接口补
            if ("UA40006".equals(returnCode)) {
                return new BindResult(true, null, null);
            }
            String returnMsg = text(root, "returnMsg");
            log.warn("易宝结算卡绑定失败 merchantNo={} code={} msg={}",
                    merchantNo, returnCode, returnMsg);
            return new BindResult(false, null, returnMsg == null ? "绑定失败（" + returnCode + "）" : returnMsg);
        } catch (Exception e) {
            log.error("易宝结算卡绑定异常 merchantNo={} err={}", merchantNo, e.getMessage());
            return new BindResult(false, null, "绑定请求异常：" + e.getMessage());
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

    /** 绑定结果。 */
    public record BindResult(boolean accepted, String settleCardId, String failReason) {
    }
}
