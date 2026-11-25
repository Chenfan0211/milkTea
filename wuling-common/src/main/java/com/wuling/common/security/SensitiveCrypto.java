package com.wuling.common.security;

import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 敏感字段加解密（AES-256-GCM）。
 *
 * <p><b>为什么用 GCM 而不是 CBC</b>：GCM 自带完整性校验（AEAD），
 * 密文被篡改会在解密时直接失败，而 CBC 需要额外 HMAC 且容易误用。
 *
 * <p><b>密钥来源</b>：环境变量 {@code APP_SENSITIVE_KEY}（Base64 编码的 32 字节）。
 * 不落仓库、不入库。未配置时<b>不提供</b>任何「默认密钥」——那等于没加密，
 * 反而给人安全感而实际裸奔。
 *
 * <p><b>密钥轮换</b>：密文格式 {@code v1:<base64(iv||ciphertext||tag)>}，
 * 前缀留作版本号。轮换时新增 v2 解密分支即可，历史数据无需重写。
 */
public final class SensitiveCrypto {

    private static final Logger log = LoggerFactory.getLogger(SensitiveCrypto.class);

    private static final String PREFIX = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static volatile SecretKeySpec key;

    private SensitiveCrypto() {
    }

    /**
     * 初始化密钥。由配置类在启动时调用一次。
     *
     * @param base64Key Base64 编码的 32 字节密钥；为空则视为未启用加密
     */
    public static synchronized void initialize(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            key = null;
            return;
        }
        byte[] raw = Base64.getDecoder().decode(base64Key.trim());
        if (raw.length != 32) {
            throw new IllegalStateException(
                    "APP_SENSITIVE_KEY 必须是 Base64 编码的 32 字节（当前 " + raw.length + " 字节）");
        }
        key = new SecretKeySpec(raw, "AES");
        log.info("敏感字段加密已启用（AES-256-GCM）");
    }

    /** 是否已配置密钥。 */
    public static boolean isEnabled() {
        return key != null;
    }

    /**
     * 加密。已加密的值（含 {@code v1:} 前缀）原样返回，保证幂等。
     *
     * @throws BusinessException 未配置密钥时（不允许明文入库）
     */
    public static String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        if (plaintext.startsWith(PREFIX)) {
            return plaintext;
        }
        if (key == null) {
            throw new BusinessException(ResultCode.ERROR,
                    "敏感字段加密未配置（APP_SENSITIVE_KEY），拒绝明文入库");
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("敏感字段加密失败", e);
        }
    }

    /**
     * 解密。非 {@code v1:} 前缀的值视为历史明文，原样返回
     * （兼容上线前已存在的数据，避免迁移期读失败）。
     */
    public static String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) {
            return ciphertext;
        }
        if (!ciphertext.startsWith(PREFIX)) {
            return ciphertext;
        }
        if (key == null) {
            throw new BusinessException(ResultCode.ERROR,
                    "敏感字段加密未配置（APP_SENSITIVE_KEY），无法解密");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext.substring(PREFIX.length()));
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            byte[] cipherText = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("敏感字段解密失败（密钥是否正确？）", e);
        }
    }

    /** 证件号掩码：保留前 4 后 4。 */
    public static String maskIdCard(String idCard) {
        if (idCard == null || idCard.length() < 9) {
            return "****";
        }
        return idCard.substring(0, 4) + "**********" + idCard.substring(idCard.length() - 4);
    }

    /** 银行卡号掩码：保留前 4 后 4。 */
    public static String maskBankCard(String cardNo) {
        if (cardNo == null || cardNo.length() < 9) {
            return "****";
        }
        return cardNo.substring(0, 4) + " **** **** " + cardNo.substring(cardNo.length() - 4);
    }

    /** 手机号掩码。 */
    public static String maskMobile(String mobile) {
        return com.wuling.common.logging.SensitiveDataMasker.maskPhone(mobile);
    }
}
