package com.wuling.subject.onboard;

/**
 * 易宝入驻端口。
 *
 * <p>真正的易宝 API 调用在 trade-service（承载 YOP SDK），server 通过本端口
 * 委托。这样保证易宝 SDK 与密钥只存在于一个模块，server 完全不知道
 * 易宝的报文格式。
 *
 * <p><b>未启用易宝时</b>：由 {@code NoopYeepayOnboardPort} 兜底，
 * 提交时明确拒绝并提示「易宝通道未启用」，而不是静默成功。
 */
public interface YeepayOnboardPort {

    /**
     * 提交入驻申请。
     *
     * @param onboard 本地入驻记录（已含 requestNo / signType）
     * @param request 原始资料（含加密前的证件号与手机号）
     */
    OnboardResult submit(MerchantOnboard onboard, com.wuling.subject.onboard.dto.MerchantOnboardRequest request);

    /** 查询入驻进度。 */
    OnboardQueryResult query(String requestNo);

    /**
     * 入驻提交结果。
     *
     * @param accepted          是否受理
     * @param applicationNo     易宝申请单编号
     * @param merchantNo        易宝子商户编号（可能受理时就返回，也可能审核通过后才返回）
     * @param applicationStatus 易宝申请状态
     * @param failReason        失败原因
     */
    record OnboardResult(boolean accepted, String applicationNo, String merchantNo,
                         String applicationStatus, String failReason) {
    }

    /** 入驻进度查询结果。 */
    record OnboardQueryResult(String applicationStatus, String merchantNo, String rejectReason) {
    }
}
