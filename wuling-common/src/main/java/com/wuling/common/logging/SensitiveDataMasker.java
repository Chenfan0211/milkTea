package com.wuling.common.logging;

import java.util.regex.Pattern;

/**
 * 敏感数据脱敏工具。
 *
 * <p>为什么需要：请求日志会打印入参，而本项目接口入参包含大量敏感字段
 * （JWT、手机号、微信 encryptedData/iv、支付回调签名、短信验证码）。
 * 这些一旦写入日志文件，等于把凭据落盘，且 {@code docker logs} 可被拉取。
 *
 * <p>策略：<b>默认脱敏</b>，仅在显式开启 {@code app.log.request.full=true}
 * 时才原样输出（用于临时排查，排查完必须关闭）。
 *
 * <p>脱敏规则：
 * <ul>
 *   <li>手机号：138****8000（保留前3后4）</li>
 *   <li>token / sign / encryptedData / iv / sessionKey / password：保留前若干位 + ***</li>
 * </ul>
 */
public final class SensitiveDataMasker {

    /** 需要整体脱敏的字段名。 */
    private static final String[] SENSITIVE_KEYS = {
            "token", "accessToken", "refreshToken", "authorization",
            "password", "pwd", "secret",
            "sign", "signature",
            "encryptedData", "iv", "sessionKey",
            "code", "smsCode"
    };

    /** 11 位手机号。 */
    private static final Pattern PHONE = Pattern.compile("(1[3-9]\\d)(\\d{4})(\\d{4})");

    /**
     * base64 内联图片（data URL）。
     *
     * <p><b>为什么必须截断</b>：2026-11-23 事故中，日志里出现
     * {@code "image":"data:image/png;base64,...(共2217873字符)"} ——
     * 单次请求写入 2MB，日志文件迅速膨胀且完全无法阅读。
     * 这类值对排查毫无价值（真正的问题是「它太长」，长度信息已足够）。
     */
    private static final Pattern DATA_URL = Pattern.compile(
            "(data:[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+;base64,)([A-Za-z0-9+/=]{64,})");

    private SensitiveDataMasker() {
    }

    /** 手机号脱敏：138****8000 */
    public static String maskPhone(String phone) {
        if (phone == null) {
            return null;
        }
        return PHONE.matcher(phone).replaceAll("$1****$3");
    }

    /** 通用凭据脱敏：保留前 6 位便于比对，其余打码。 */
    public static String maskCredential(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (value.length() <= 6) {
            return "***";
        }
        return value.substring(0, 6) + "***(" + value.length() + "位)";
    }

    /** 该字段名是否属于敏感字段。 */
    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        for (String sensitive : SENSITIVE_KEYS) {
            if (sensitive.equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 对 JSON 文本做整体脱敏（按字段名替换值）。
     *
     * <p>用正则而非解析 JSON：日志路径要轻量、不能因畸形 JSON 抛异常。
     *
     * @param json 原始 JSON 文本
     * @return 脱敏后的文本
     */
    public static String maskJson(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        String result = json;
        for (String key : SENSITIVE_KEYS) {
            result = result.replaceAll(
                    "(?i)(\"" + key + "\"\\s*:\\s*\")([^\"]{0,4096})(\")",
                    "$1***$3");
        }
        result = PHONE.matcher(result).replaceAll("$1****$3");
        // 内联 base64 图片：只保留类型前缀与长度，避免 MB 级内容灌进日志
        result = truncateDataUrls(result);
        return result;
    }

    /**
     * 截断 data URL 的 base64 主体。
     *
     * <p>输出形如 {@code data:image/png;base64,***TRUNCATED(2217800)}，
     * 保留「是什么类型、有多大」这两个排查关键信息，
     * 丢掉没有任何诊断价值的编码内容。
     */
    public static String truncateDataUrls(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        java.util.regex.Matcher m = DATA_URL.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String prefix = m.group(1);
            String body = m.group(2);
            m.appendReplacement(sb,
                    java.util.regex.Matcher.quoteReplacement(
                            prefix + "***TRUNCATED(" + body.length() + ")"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 截断超长文本，避免日志被单个大 body 淹没。 */
    public static String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...(共" + text.length() + "字符)";
    }
}
