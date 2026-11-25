package com.wuling.subject.onboard.dto;

import lombok.Data;

/**
 * 入驻申请入参（小程序提交）。
 *
 * <p>三类主体共用同一个 DTO，必填项由服务层按 {@code signType} 校验：
 * <ul>
 *   <li>ENTERPRISE / INDIVIDUAL：营业执照 + 信用代码 + 法人证件；</li>
 *   <li>MICRO：仅身份证正反面，无营业执照。</li>
 * </ul>
 */
@Data
public class MerchantOnboardRequest {

    /** ENTERPRISE / INDIVIDUAL / MICRO */
    private String signType;

    /** 法人/经营者姓名 */
    private String legalName;

    /** 证件号码（明文传输，服务端加密入库，永不返回明文） */
    private String legalLicenceNo;

    /** 证件人像面（易宝资质文件 URL） */
    private String legalLicenceFrontUrl;

    /** 证件非人像面 */
    private String legalLicenceBackUrl;

    /** 营业执照（企业/个体必填） */
    private String licenceUrl;

    /** 统一社会信用代码 */
    private String licenceNo;

    /** 商户签约名，须与证照一致 */
    private String signName;

    /** 商户简称 */
    private String shortName;

    private String contactName;

    /** 联系人手机号（明文传输，服务端加密入库） */
    private String contactMobile;

    private String contactEmail;

    private String province;
    private String city;
    private String district;
    private String address;
}
