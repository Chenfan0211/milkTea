package com.wuling.user.sms;

/**
 * 短信通道抽象。
 *
 * 设计目的：降级方案（手动手机号 + 验证码）需要真实短信通道，
 * 但通道配置（腾讯云/阿里云签名与模板）通常滞后于开发。
 * 因此这里抽象出接口：开发期用 ConsoleSmsProvider，上线换 TencentSmsProvider 即可。
 */
public interface SmsProvider {

    /** 通道标识，用于日志与健康检查 */
    String channel();

    /**
     * 发送验证码短信。
     *
     * @param phone 手机号
     * @param code  验证码
     * @return 是否发送成功
     */
    boolean send(String phone, String code);
}
