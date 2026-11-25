package com.wuling.finance.payee.dto;

import lombok.Data;

/**
 * 绑定结算卡的入参。
 *
 * <p><b>为什么不直接复用 {@code PayoutAccount} 实体</b>：实体上的
 * {@code bankCardNoEnc} 是<b>数据库列</b>（存 AES 密文），
 * 若前端也往这个字段传值，后续维护者极易误以为「前端已经加密了」
 * 而在服务层跳过加密，导致明文落盘。
 *
 * <p>因此入参独立成 DTO，字段名用 {@code bankCardNo}（明文语义），
 * 由服务层负责加密与掩码。
 */
@Data
public class BindCardRequest {

    /** PUBLIC 对公 / PRIVATE 对私 */
    private String accountType;

    /** 开户名，须与签约主体一致 */
    private String accountName;

    /** 银行卡号（明文；服务端加密入库，永不回传） */
    private String bankCardNo;

    /** 开户总行编码 */
    private String bankCode;

    /** 开户支行编码（农信社 / 小额农行建议填） */
    private String branchCode;

    /** 开户行名称（展示用） */
    private String bankName;
}
