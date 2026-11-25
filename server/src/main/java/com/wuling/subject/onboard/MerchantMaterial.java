package com.wuling.subject.onboard;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 经营者入驻资料（实名与证照）。
 *
 * <p>与企业/个体/小微三类主体对应的必填项不同：
 * <ul>
 *   <li>企业/个体：营业执照 + 法人证件；</li>
 *   <li>小微：仅身份证正反面。</li>
 * </ul>
 * 敏感字段（证件号、手机号）加密存储 + 掩码列展示。
 */
@Data
@TableName("subject_merchant_material")
public class MerchantMaterial {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long subjectId;
    /** 法人/经营者姓名 */
    private String legalName;
    /** 证件号（AES 加密） */
    private String legalLicenceNoEnc;
    /** 证件号掩码 */
    private String legalLicenceMask;
    private String legalLicenceFrontUrl;
    private String legalLicenceBackUrl;
    /** 营业执照（企业/个体必填） */
    private String licenceUrl;
    private String licenceNo;
    /** 商户签约名，须与证照一致 */
    private String signName;
    private String shortName;
    private String contactName;
    private String contactMobileEnc;
    private String contactMobileMask;
    private String contactEmail;
    private String province;
    private String city;
    private String district;
    private String address;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
