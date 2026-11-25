package com.wuling.trade.pay.yeepay;

import com.yeepay.yop.sdk.service.common.YopClient;
import com.yeepay.yop.sdk.service.common.request.YopRequest;
import com.yeepay.yop.sdk.service.common.response.YopResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 易宝 API 调用封装。
 *
 * <p><b>为什么不用业务 SDK</b>：业务 SDK（{@code yop-java-sdk-*} 的 service 包）
 * 会把所有易宝产品（发票 / 钱包 / 银联 …）的模型类都拉进来，体积大且与实际
 * 用到的面（入驻 / 分账 / 结算 / 提现）严重不匹配。基础 SDK + {@link YopRequest}
 * 拼参数的可控性更好，也便于在日志里精确控制敏感字段。
 *
 * <p><b>参数编码</b>：易宝要求「非 ASCII 与特殊字符做两次 URL 编码」。
 * SDK 内部会做一次，调用方需要保证传的是原始值而不是已编码值——
 * 因此本类<b>不做任何预编码</b>，避免双重编码导致中文乱码。
 *
 * <p><b>日志纪律</b>：只记录路径、请求号、返回码，
 * <b>绝不</b>记录完整报文（含证件号、银行卡号、金额明细）。
 */
@Component
@ConditionalOnProperty(name = "app.pay.yeepay.channel", havingValue = "yeepay")
public class YeepayApiClient {

    private static final Logger log = LoggerFactory.getLogger(YeepayApiClient.class);

    private final YopClient yopClient;

    public YeepayApiClient(YopClient yopClient) {
        this.yopClient = yopClient;
    }

    /**
     * 发起 POST 请求。
     *
     * @param path   接口路径，如 {@code /rest/v2.0/mer/register/saas/merchant}
     * @param params 表单参数（原始值，不做预编码）
     * @return 响应对象
     */
    public YopResponse post(String path, Map<String, String> params) {
        YopRequest request = new YopRequest(path, "POST");
        if (params != null) {
            params.forEach(request::addParameter);
        }
        try {
            long start = System.currentTimeMillis();
            YopResponse response = yopClient.request(request);
            log.info("易宝调用 path={} requestNo={} cost={}ms",
                    path, params == null ? null : params.get("requestNo"),
                    System.currentTimeMillis() - start);
            return response;
        } catch (Exception e) {
            log.error("易宝调用失败 path={} requestNo={} err={}",
                    path, params == null ? null : params.get("requestNo"), e.getMessage());
            throw new YeepayApiException("易宝接口调用失败：" + e.getMessage(), e);
        }
    }

    /**
     * 发起 GET 请求（查询类接口）。
     */
    public YopResponse get(String path, Map<String, String> params) {
        YopRequest request = new YopRequest(path, "GET");
        if (params != null) {
            params.forEach(request::addParameter);
        }
        try {
            return yopClient.request(request);
        } catch (Exception e) {
            log.error("易宝查询失败 path={} err={}", path, e.getMessage());
            throw new YeepayApiException("易宝接口查询失败：" + e.getMessage(), e);
        }
    }

    /** 易宝调用异常（网络或 SDK 层）。 */
    public static class YeepayApiException extends RuntimeException {
        public YeepayApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
