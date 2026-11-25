package com.wuling.subject.onboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.common.security.SensitiveCrypto;
import com.wuling.subject.onboard.dto.MerchantOnboardRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link YeepayOnboardPort} 的远程实现：转发到 trade-service 的
 * {@code /internal/yeepay/onboard}。
 *
 * <p><b>为什么由 server 发送明文证件号</b>：trade-service 需要原始值才能
 * 组装易宝请求。传输发生在内网（{@code /internal/**} 不经网关、各服务仅监听
 * 127.0.0.1），且不落日志（入参用 {@code requestNo} 与掩码打点）。
 *
 * <p><b>fail-closed</b>：网络异常一律返回 accepted=false，由调用方提示重试。
 * 不做自动重试——入驻是低频操作，重试由运营手动触发更可控。
 */
@Component
public class RemoteYeepayOnboardAdapter implements YeepayOnboardPort {

    private static final Logger log = LoggerFactory.getLogger(RemoteYeepayOnboardAdapter.class);

    private final RestClient internalRestClient;
    private final ObjectMapper objectMapper;
    private final boolean yeepayEnabled;

    public RemoteYeepayOnboardAdapter(@Qualifier("internalRestClient") RestClient internalRestClient,
                                      ObjectMapper objectMapper,
                                      @Value("${app.pay.yeepay.channel:}") String yeepayChannel) {
        this.internalRestClient = internalRestClient;
        this.objectMapper = objectMapper;
        this.yeepayEnabled = "yeepay".equalsIgnoreCase(
                yeepayChannel == null ? "" : yeepayChannel.trim());
    }

    @Override
    @SuppressWarnings("unchecked")
    public OnboardResult submit(MerchantOnboard onboard, MerchantOnboardRequest request) {
        if (!yeepayEnabled) {
            return new OnboardResult(false, null, null, null,
                    "易宝通道未启用（app.pay.yeepay.channel 未配置为 yeepay）");
        }
        Map<String, Object> body = new HashMap<>();
        body.put("requestNo", onboard.getRequestNo());
        body.put("subjectId", onboard.getSubjectId());
        body.put("signType", onboard.getSignType());
        body.put("legalName", request.getLegalName());
        body.put("legalLicenceNo", request.getLegalLicenceNo());
        body.put("legalLicenceFrontUrl", request.getLegalLicenceFrontUrl());
        body.put("legalLicenceBackUrl", request.getLegalLicenceBackUrl());
        body.put("licenceUrl", request.getLicenceUrl());
        body.put("licenceNo", request.getLicenceNo());
        body.put("signName", request.getSignName());
        body.put("shortName", request.getShortName());
        body.put("contactName", request.getContactName());
        body.put("contactMobile", request.getContactMobile());
        body.put("contactEmail", request.getContactEmail());
        body.put("province", request.getProvince());
        body.put("city", request.getCity());
        body.put("district", request.getDistrict());
        body.put("address", request.getAddress());

        try {
            Map<String, Object> response = internalRestClient.post()
                    .uri("/internal/yeepay/onboard")
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (response == null) {
                return new OnboardResult(false, null, null, null, "入驻服务无响应");
            }
            boolean accepted = Boolean.TRUE.equals(response.get("accepted"));
            return new OnboardResult(
                    accepted,
                    text(response.get("applicationNo")),
                    text(response.get("merchantNo")),
                    text(response.get("applicationStatus")),
                    text(response.get("failReason")));
        } catch (Exception e) {
            // 注意：异常信息可能含请求体，只记 requestNo 与异常类型
            log.error("易宝入驻提交失败 requestNo={} subjectId={} err={}",
                    onboard.getRequestNo(), onboard.getSubjectId(), e.getMessage());
            return new OnboardResult(false, null, null, null,
                    "入驻请求失败，请稍后重试");
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public OnboardQueryResult query(String requestNo) {
        if (!yeepayEnabled) {
            return null;
        }
        try {
            Map<String, Object> response = internalRestClient.post()
                    .uri("/internal/yeepay/onboard/query")
                    .body(Map.of("requestNo", requestNo))
                    .retrieve()
                    .body(Map.class);
            if (response == null) {
                return null;
            }
            return new OnboardQueryResult(
                    text(response.get("applicationStatus")),
                    text(response.get("merchantNo")),
                    text(response.get("rejectReason")));
        } catch (Exception e) {
            log.warn("查询易宝入驻进度失败 requestNo={} err={}", requestNo, e.getMessage());
            return null;
        }
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
