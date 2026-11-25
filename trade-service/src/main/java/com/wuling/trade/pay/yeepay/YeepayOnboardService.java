package com.wuling.trade.pay.yeepay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeepay.yop.sdk.service.common.response.YopResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 易宝子商户入驻（{@code /rest/v2.0/mer/register/saas/merchant} 与 {@code /saas/micro}）。
 *
 * <p><b>两类接口</b>：
 * <ul>
 *   <li>企业 / 个体工商户 → {@code saas/merchant}，需营业执照 + 法人证件；</li>
 *   <li>自然人（小微）→ {@code saas/micro}，仅身份证正反面。</li>
 * </ul>
 *
 * <p><b>businessRole 固定为 SETTLED_MERCHANT</b>（入驻商户）：既能接收分账，
 * 又能通过自助结算把待结算账户的钱打到结算卡。若用 SHARE_MERCHANT
 * （纯分账接收方）只能结算到银行账户，没有待结算账户这一层，
 * 与我们「平台审核后才出款」的模型不符。
 *
 * <p><b>functionService 传 SHARE</b>：开通分账服务，否则无法作为分账接收方。
 */
@Service
@ConditionalOnProperty(name = "app.pay.yeepay.channel", havingValue = "yeepay")
public class YeepayOnboardService {

    private static final Logger log = LoggerFactory.getLogger(YeepayOnboardService.class);

    private static final String PATH_MERCHANT = "/rest/v2.0/mer/register/saas/merchant";
    private static final String PATH_MICRO = "/rest/v2.0/mer/register/saas/micro";
    private static final String PATH_QUERY = "/rest/v2.0/mer/register/query";

    private final YeepayApiClient apiClient;
    private final YeepayProperties properties;
    private final ObjectMapper objectMapper;

    public YeepayOnboardService(YeepayApiClient apiClient,
                                YeepayProperties properties,
                                ObjectMapper objectMapper) {
        this.apiClient = apiClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 提交入驻申请。
     *
     * @param onboard 入驻入参（含 requestNo / signType / 各种资料）
     * @return 接入结果
     */
    public OnboardResult submit(OnboardInput onboard) {
        boolean micro = "MICRO".equalsIgnoreCase(onboard.signType());
        String path = micro ? PATH_MICRO : PATH_MERCHANT;

        Map<String, String> params = new HashMap<>();
        params.put("requestNo", onboard.requestNo());
        params.put("businessRole", "SETTLED_MERCHANT");
        params.put("parentMerchantNo", requireParentMerchantNo());
        params.put("notifyUrl", properties.notifyUrl("/api/v1/app/yeepay/notify/merchant"));
        params.put("functionService", "[\"SHARE\"]");
        params.put("merchantSubjectInfo", toJson(subjectInfo(onboard)));
        params.put("merchantContactInfo", toJson(contactInfo(onboard)));
        params.put("businessAddressInfo", toJson(addressInfo(onboard)));
        params.put("merchantCorporationInfo", toJson(corporationInfo(onboard)));
        // settlementAccountInfo 是易宝「非必填」参数。
        // 本项目选择「入驻通过后再单独绑卡」（见小程序 settle-card 页），
        // 传入空对象会被易宝按「结算账户缺失」处理而驳回，故此时完全不传。
        // 入驻通过后由 settle/settle-card/add 补绑，绑卡成功才能提现。
        if (onboard.bankCardNo() != null && !onboard.bankCardNo().isBlank()) {
            params.put("settlementAccountInfo", toJson(settlementInfo(onboard)));
        }
        params.put("productInfo", toJson(defaultProducts()));
        if (!micro) {
            params.put("industryCategoryInfo", toJson(industryCategory()));
        }

        try {
            YopResponse response = apiClient.post(path, params);
            JsonNode root = parse(response);
            String returnCode = text(root, "returnCode");
            String returnMsg = text(root, "returnMsg");
            if (!"NIG00000".equals(returnCode)) {
                log.warn("易宝入驻未受理 requestNo={} code={} msg={}",
                        onboard.requestNo(), returnCode, returnMsg);
                return new OnboardResult(false, null, null, null,
                        returnMsg == null ? "入驻申请被拒绝（" + returnCode + "）" : returnMsg);
            }
            return new OnboardResult(true,
                    text(root, "applicationNo"),
                    text(root, "merchantNo"),
                    text(root, "applicationStatus"),
                    null);
        } catch (Exception e) {
            log.error("易宝入驻调用异常 requestNo={} err={}", onboard.requestNo(), e.getMessage());
            return new OnboardResult(false, null, null, null, "入驻请求异常：" + e.getMessage());
        }
    }

    /** 查询入驻进度。 */
    public OnboardQueryResult query(String requestNo) {
        Map<String, String> params = new HashMap<>();
        params.put("requestNo", requestNo);
        params.put("parentMerchantNo", requireParentMerchantNo());
        try {
            YopResponse response = apiClient.post(PATH_QUERY, params);
            JsonNode root = parse(response);
            return new OnboardQueryResult(
                    text(root, "applicationStatus"),
                    text(root, "merchantNo"),
                    text(root, "auditOpinion"));
        } catch (Exception e) {
            log.warn("查询易宝入驻进度失败 requestNo={} err={}", requestNo, e.getMessage());
            return null;
        }
    }

    // ---------------- 报文组装 ----------------

    private Map<String, Object> subjectInfo(OnboardInput onboard) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (onboard.licenceUrl() != null) {
            map.put("licenceUrl", onboard.licenceUrl());
        }
        map.put("signName", onboard.signName());
        map.put("signType", onboard.signType());
        if (onboard.licenceNo() != null) {
            map.put("licenceNo", onboard.licenceNo());
        }
        if (onboard.shortName() != null) {
            map.put("shortName", onboard.shortName());
        }
        return map;
    }

    private Map<String, Object> corporationInfo(OnboardInput onboard) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("legalName", onboard.legalName());
        map.put("legalLicenceType", "ID_CARD");
        map.put("legalLicenceNo", onboard.legalLicenceNo());
        map.put("legalLicenceFrontUrl", onboard.legalLicenceFrontUrl());
        if (onboard.legalLicenceBackUrl() != null) {
            map.put("legalLicenceBackUrl", onboard.legalLicenceBackUrl());
        }
        return map;
    }

    private Map<String, Object> contactInfo(OnboardInput onboard) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("contactName", onboard.contactName() == null
                ? onboard.legalName() : onboard.contactName());
        map.put("contactMobile", onboard.contactMobile());
        if (onboard.contactEmail() != null) {
            map.put("contactEmail", onboard.contactEmail());
        }
        map.put("contactLicenceNo", onboard.legalLicenceNo());
        return map;
    }

    private Map<String, Object> addressInfo(OnboardInput onboard) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("province", nvl(onboard.province(), "北京市"));
        map.put("city", nvl(onboard.city(), "北京市"));
        map.put("district", nvl(onboard.district(), "朝阳区"));
        map.put("address", nvl(onboard.address(), "线上经营"));
        return map;
    }

    /**
     * 结算账户（仅在调用方显式提供银行卡时使用）。
     *
     * <p><b>当前默认不走这条路</b>：本项目选择「入驻通过后再单独绑卡」，
     * 由 {@code settle/settle-card/add} 完成，因此 {@link #submit} 通常不传
     * {@code settlementAccountInfo}。
     *
     * <p>保留本方法是为了将来支持「入驻即绑卡」：那时只需让入参带上银行卡，
     * 无需改动报文组装逻辑。
     *
     * <p><b>刻意不传 {@code settlementDirection}</b>：该字段的取值（自助结算
     * 对应哪个枚举）需与易宝确认，猜测填写反而会导致驳回。确认后再补。
     */
    private Map<String, Object> settlementInfo(OnboardInput onboard) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("bankAccountType", onboard.bankAccountType());
        map.put("bankCardNo", onboard.bankCardNo());
        if (onboard.bankCode() != null) {
            map.put("bankCode", onboard.bankCode());
        }
        return map;
    }

    private Map<String, Object> industryCategory() {
        Map<String, Object> map = new LinkedHashMap<>();
        // 零售 / 食品饮料 —— 具体类目编码需按易宝最新类目表核对
        map.put("primaryIndustryCategory", "5812");
        map.put("secondaryIndustryCategory", "5812");
        return map;
    }

    /**
     * 开通产品：T1 结算 + 分账。
     *
     * <p>费率由商务确定，这里给 0.1% 占位；
     * 实际值以合同为准，上线前必须与易宝确认后覆盖。
     */
    private Object defaultProducts() {
        Map<String, Object> t1 = new LinkedHashMap<>();
        t1.put("productCode", "T1");
        t1.put("rateType", "SINGLE_PERCENT");
        t1.put("percentRate", "0.1");
        return java.util.List.of(t1);
    }

    // ---------------- 工具 ----------------

    private String requireParentMerchantNo() {
        String platform = properties.getPlatformMerchantNo();
        if (platform == null || platform.isBlank()) {
            throw new IllegalStateException(
                    "易宝平台商商编未配置（app.pay.yeepay.platform-merchant-no）："
                            + "子商户入网必须以平台商为上级，否则无法接收分账");
        }
        return platform;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("易宝报文序列化失败", e);
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

    private String nvl(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 入驻入参。 */
    public record OnboardInput(String requestNo, String signType, String legalName,
                               String legalLicenceNo, String legalLicenceFrontUrl,
                               String legalLicenceBackUrl, String licenceUrl, String licenceNo,
                               String signName, String shortName, String contactName,
                               String contactMobile, String contactEmail,
                               String province, String city, String district, String address,
                               String bankAccountType, String bankCardNo, String bankCode) {
    }

    /** 入驻结果。 */
    public record OnboardResult(boolean accepted, String applicationNo, String merchantNo,
                                String applicationStatus, String failReason) {
    }

    /** 入驻进度查询结果。 */
    public record OnboardQueryResult(String applicationStatus, String merchantNo, String rejectReason) {
    }
}
