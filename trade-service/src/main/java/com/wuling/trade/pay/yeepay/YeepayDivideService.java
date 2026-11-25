package com.wuling.trade.pay.yeepay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeepay.yop.sdk.service.common.response.YopResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 易宝订单分账（{@code /rest/v1.0/divide/apply}）。
 *
 * <p>把平台待分账账户里的订单资金分给经营者子商户。
 *
 * <p><b>三条易宝硬约束</b>：
 * <ol>
 *   <li><b>限流</b>：同一订单两次分账需间隔 2-3 秒，否则报「请不要频繁调用」；</li>
 *   <li><b>次数上限</b>：同一订单多次分账会撞 {@code OPR21006}，故一次调用要把
 *       该订单<b>所有已入驻</b>的接收方放在同一个 {@code divideDetail} 数组里；</li>
 *   <li><b>原子性</b>：一次请求中任一明细失败，则<b>全部明细</b>都失败——
 *       因此同一批次里不能混入「可能失败」的接收方。</li>
 * </ol>
 *
 * <p><b>部分分账</b>：未入驻的收款方不分，剩余金额留在 {@code unSplitAmount}，
 * 待其入驻后再次分账（受次数上限约束，需合并同订单的待分方一起补）。
 */
@Service
@ConditionalOnProperty(name = "app.pay.yeepay.channel", havingValue = "yeepay")
public class YeepayDivideService {

    private static final Logger log = LoggerFactory.getLogger(YeepayDivideService.class);

    private static final String PATH_APPLY = "/rest/v1.0/divide/apply";
    private static final String PATH_QUERY = "/rest/v1.0/divide/query";
    private static final String PATH_COMPLETE = "/rest/v1.0/divide/complete";
    private static final String PATH_BACK = "/rest/v1.0/divide/back";

    private final YeepayApiClient apiClient;
    private final YeepayProperties properties;
    private final ObjectMapper objectMapper;

    public YeepayDivideService(YeepayApiClient apiClient,
                               YeepayProperties properties,
                               ObjectMapper objectMapper) {
        this.apiClient = apiClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 申请订单分账。
     *
     * @param orderId         商户订单号（本项目即 orderNo）
     * @param merchantNo      收款商户（平台商）编号
     * @param divideRequestId 分账请求号（唯一，幂等键）
     * @param receivers       分账接收方列表
     */
    public DivideResult apply(String orderId, String merchantNo, String divideRequestId,
                              List<DivideReceiver> receivers) {
        if (receivers == null || receivers.isEmpty()) {
            return new DivideResult(false, null, null, "分账接收方为空");
        }
        Map<String, String> params = new HashMap<>();
        params.put("parentMerchantNo", properties.getMerchantNo());
        params.put("merchantNo", merchantNo);
        params.put("orderId", orderId);
        params.put("divideRequestId", divideRequestId);
        params.put("divideRule", "AMOUNT");
        params.put("divideDetail", toJson(buildDetail(receivers)));
        // 实时分账订单必须解冻剩余；延迟分账订单不能传，这里保持不传
        try {
            YopResponse response = apiClient.post(PATH_APPLY, params);
            JsonNode root = parse(response);
            String code = text(root, "code");
            if (!"OPR00000".equals(code)) {
                String message = text(root, "message");
                log.warn("易宝分账未受理 orderId={} requestId={} code={} msg={}",
                        orderId, divideRequestId, code, message);
                // 处理中不算失败：交给查询确认，避免误判后重复分账
                if ("PROCESSING".equals(text(root, "status"))) {
                    return new DivideResult(true, "PROCESSING", text(root, "uniqueDivideNo"),
                            null, List.of());
                }
                return new DivideResult(false, null, null,
                        message == null ? "分账失败（" + code + "）" : message, List.of());
            }
            return new DivideResult(true,
                    text(root, "status"),
                    text(root, "uniqueDivideNo"),
                    null,
                    parseDetails(root));
        } catch (Exception e) {
            log.error("易宝分账调用异常 orderId={} requestId={} err={}",
                    orderId, divideRequestId, e.getMessage());
            return new DivideResult(false, null, null, "分账请求异常：" + e.getMessage(), List.of());
        }
    }

    /**
     * 解析分账响应里的明细数组。
     *
     * <p><b>为什么必须解析</b>：退款归集要求传「易宝分账明细订单号」
     * （{@code divideDetailNo}），这是<b>明细级</b>而非批次级的标识。
     * 只存批次级 {@code uniqueDivideNo} 无法定位到某个接收方，
     * 会导致部分分账场景无法退款。
     *
     * <p>响应中的 {@code divideDetail} 是 JSON 字符串数组，每项含
     * {@code divideDetailNo} / {@code ledgerNo} / {@code amount} 等。
     * 调用方按 {@code ledgerNo}（= 接收方商编）映射回本地结算记录。
     */
    private List<DetailItem> parseDetails(JsonNode root) {
        JsonNode detailNode = root.path("divideDetail");
        if (detailNode.isMissingNode() || detailNode.isNull()) {
            return List.of();
        }
        List<DetailItem> items = new ArrayList<>();
        try {
            // divideDetail 可能是「JSON 字符串」也可能已是数组，两种都兼容
            JsonNode array = detailNode.isTextual()
                    ? objectMapper.readTree(detailNode.asText())
                    : detailNode;
            if (!array.isArray()) {
                return List.of();
            }
            for (JsonNode node : array) {
                items.add(new DetailItem(
                        text(node, "ledgerNo"),
                        text(node, "divideDetailNo"),
                        text(node, "amount"),
                        text(node, "status")));
            }
        } catch (Exception e) {
            log.warn("解析分账明细失败，将无法回写 divideDetailNo err={}", e.getMessage());
        }
        return items;
    }

    /**
     * 完结分账：把订单剩余可分账金额全部给收款商户（平台自己）。
     *
     * <p>用于「所有经营者都已分账完毕，剩余平台份额落回余额」的场景。
     * 不调用的话，平台份额会一直留在待分账账户无法使用。
     */
    public DivideResult complete(String orderId, String merchantNo) {
        Map<String, String> params = new HashMap<>();
        params.put("parentMerchantNo", properties.getMerchantNo());
        params.put("merchantNo", merchantNo);
        params.put("orderId", orderId);
        params.put("divideRequestId", "CP" + orderId);
        try {
            YopResponse response = apiClient.post(PATH_COMPLETE, params);
            JsonNode root = parse(response);
            String code = text(root, "code");
            if (!"OPR00000".equals(code)) {
                return new DivideResult(false, null, null, text(root, "message"));
            }
            return new DivideResult(true, text(root, "status"), text(root, "uniqueDivideNo"), null);
        } catch (Exception e) {
            log.error("易宝完结分账异常 orderId={} err={}", orderId, e.getMessage());
            return new DivideResult(false, null, null, "完结分账异常：" + e.getMessage());
        }
    }

    /** 查询分账结果（补偿回调丢失）。 */
    public DivideQueryResult query(String orderId, String divideRequestId) {
        Map<String, String> params = new HashMap<>();
        params.put("parentMerchantNo", properties.getMerchantNo());
        params.put("orderId", orderId);
        params.put("divideRequestId", divideRequestId);
        try {
            YopResponse response = apiClient.get(PATH_QUERY, params);
            JsonNode root = parse(response);
            return new DivideQueryResult(text(root, "status"), text(root, "message"));
        } catch (Exception e) {
            log.warn("查询易宝分账结果失败 orderId={} err={}", orderId, e.getMessage());
            return null;
        }
    }

    /**
     * 申请分账资金归还（{@code /rest/v1.0/divide/back}）。
     *
     * <p><b>为什么退款前必须调用</b>：订单分账后钱已在经营者子商户名下。
     * 若直接发起退款，资金会从平台账户出，等于平台替经营者垫付。
     * 正确做法是先把分出去的钱从子商户退回平台，再退款。
     *
     * <p><b>幂等</b>：{@code divideBackRequestId} 唯一，重试复用同一号。
     *
     * @param orderId            商户订单号
     * @param merchantNo         收款商户（平台商）
     * @param divideBackRequestId 归还请求号
     * @param receivers          归还明细（接收方 + 金额）
     */
    public DivideResult divideBack(String orderId, String merchantNo,
                                   String divideBackRequestId, List<DivideReceiver> receivers) {
        if (receivers == null || receivers.isEmpty()) {
            return new DivideResult(false, null, null, "归还明细为空");
        }
        Map<String, String> params = new HashMap<>();
        params.put("parentMerchantNo", properties.getMerchantNo());
        params.put("merchantNo", merchantNo);
        params.put("orderId", orderId);
        params.put("divideBackRequestId", divideBackRequestId);
        params.put("divideBackDetail", toJson(buildBackDetail(receivers)));
        try {
            YopResponse response = apiClient.post(PATH_BACK, params);
            JsonNode root = parse(response);
            String code = text(root, "code");
            if (!"OPR00000".equals(code)) {
                String message = text(root, "message");
                log.warn("易宝分账归还未受理 orderId={} requestId={} code={} msg={}",
                        orderId, divideBackRequestId, code, message);
                if ("PROCESSING".equals(text(root, "status"))) {
                    return new DivideResult(true, "PROCESSING", text(root, "uniqueDivideNo"), null);
                }
                return new DivideResult(false, null, null,
                        message == null ? "分账归还失败（" + code + "）" : message);
            }
            return new DivideResult(true, text(root, "status"), text(root, "uniqueDivideNo"), null);
        } catch (Exception e) {
            log.error("易宝分账归还调用异常 orderId={} err={}", orderId, e.getMessage());
            return new DivideResult(false, null, null, "分账归还请求异常：" + e.getMessage());
        }
    }

    private List<Map<String, Object>> buildBackDetail(List<DivideReceiver> receivers) {
        List<Map<String, Object>> detail = new ArrayList<>();
        for (DivideReceiver receiver : receivers) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("amount", String.format("%.2f", receiver.amountFen() / 100.0));
            item.put("ledgerNo", receiver.ledgerNo());
            item.put("ledgerType", "MERCHANT2MERCHANT");
            detail.add(item);
        }
        return detail;
    }

    private List<Map<String, Object>> buildDetail(List<DivideReceiver> receivers) {
        List<Map<String, Object>> detail = new ArrayList<>();
        for (DivideReceiver receiver : receivers) {
            Map<String, Object> item = new LinkedHashMap<>();
            // 金额单位元，两位小数
            item.put("amount", String.format("%.2f", receiver.amountFen() / 100.0));
            item.put("ledgerNo", receiver.ledgerNo());
            item.put("ledgerType", "MERCHANT2MERCHANT");
            if (receiver.description() != null) {
                item.put("divideDetailDesc", truncate(receiver.description(), 128));
            }
            detail.add(item);
        }
        return detail;
    }

    /** 易宝要求分账说明不超过 128 字节，这里按字符截断后交由 SDK 编码。 */
    private String truncate(String value, int maxBytes) {
        if (value == null) {
            return null;
        }
        if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= maxBytes) {
            return value;
        }
        return value.substring(0, Math.min(value.length(), maxBytes / 3));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("分账报文序列化失败", e);
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

    /** 分账接收方。amountFen 为分，调用方无需关心元转换。 */
    public record DivideReceiver(String ledgerNo, long amountFen, String description) {
    }

    /**
     * 分账结果。status: SUCCESS / PROCESSING / FAIL
     *
     * @param details 明细数组，每项含 {@code divideDetailNo}（退款归集必需）
     */
    public record DivideResult(boolean accepted, String status, String uniqueDivideNo,
                               String failReason, List<DetailItem> details) {

        /** 兼容无明细的调用（完结分账 / 归还 / 异常分支）。 */
        public DivideResult(boolean accepted, String status, String uniqueDivideNo, String failReason) {
            this(accepted, status, uniqueDivideNo, failReason, List.of());
        }
    }

    /**
     * 分账明细项。
     *
     * @param ledgerNo       接收方商编（用于映射回本地结算记录）
     * @param divideDetailNo 易宝分账明细单号（<b>退款归集必需</b>）
     * @param amount         金额（元）
     * @param status         明细状态
     */
    public record DetailItem(String ledgerNo, String divideDetailNo, String amount, String status) {
    }

    /** 分账查询结果。 */
    public record DivideQueryResult(String status, String message) {
    }
}
