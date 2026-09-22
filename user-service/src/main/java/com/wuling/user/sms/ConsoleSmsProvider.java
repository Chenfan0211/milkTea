package com.wuling.user.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 开发用短信通道：把验证码输出到服务端日志，不产生真实短信费用。
 *
 * 启用条件：未配置 app.sms.enabled=true（即默认）。
 * 上线接入腾讯云后，把 app.sms.enabled 置为 true 并配置密钥，本实现自动停用。
 */
@Component
@ConditionalOnProperty(name = "app.sms.enabled", havingValue = "false", matchIfMissing = true)
public class ConsoleSmsProvider implements SmsProvider {

    private static final Logger log = LoggerFactory.getLogger(ConsoleSmsProvider.class);

    @Override
    public String channel() {
        return "console";
    }

    @Override
    public boolean send(String phone, String code) {
        // 仅开发/联调：验证码打到日志，方便本地验证降级流程
        log.warn("[开发模式] 短信验证码 phone={} code={} （未配置真实短信通道，不会真实发送）", phone, code);
        return true;
    }
}
