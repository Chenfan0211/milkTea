package com.wuling.user.service;

import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.user.sms.SmsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wuling.common.redis.RedisManager;
import com.wuling.common.redis.RedisNamespace;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 短信验证码服务（降级登录方案）。
 *
 * 防护策略（对齐方案 ②A）：
 * - 6 位数字，5 分钟过期；
 * - 同手机号 60 秒内不可重发；
 * - 同手机号每日上限 10 条；
 * - 同 IP 每日上限 30 条（防脚本刷短信产生费用）；
 * - 校验错误 5 次即作废，需重新获取。
 *
 * 存储：Redis（验证码、重发间隔、各项计数）。
 */
@Service
public class SmsCodeService {

    private static final Logger log = LoggerFactory.getLogger(SmsCodeService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    // key 后缀：完整 key = RedisNamespace.SMS 前缀 + 后缀
    private static final String CODE_KEY = "code:";
    private static final String COOLDOWN_KEY = "cooldown:";
    private static final String PHONE_DAILY_KEY = "daily:phone:";
    private static final String IP_DAILY_KEY = "daily:ip:";
    private static final String FAIL_KEY = "fail:";

    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final long COOLDOWN_SECONDS = 60;
    private static final long PHONE_DAILY_LIMIT = 10;
    private static final long IP_DAILY_LIMIT = 30;
    private static final int MAX_VERIFY_FAIL = 5;

    private final RedisManager redisManager;
    private final SmsProvider smsProvider;

    public SmsCodeService(RedisManager redisManager, SmsProvider smsProvider) {
        this.redisManager = redisManager;
        this.smsProvider = smsProvider;
    }

    /** 短信命名空间的模板（single 模式下位于 app.redis.database.sms 指定的库） */
    private StringRedisTemplate redis() {
        return redisManager.template(RedisNamespace.SMS);
    }

    /** 拼接带命名空间前缀的完整 key */
    private String k(String suffix) {
        return RedisNamespace.SMS.key(suffix);
    }

    /** 手机号格式校验（中国大陆） */
    public boolean isValidPhone(String phone) {
        return StringUtils.hasText(phone) && phone.matches("^1[3-9]\\d{9}$");
    }

    /**
     * 发送验证码。
     *
     * @param phone 手机号
     * @param ip    请求方 IP（用于限流）
     */
    public void send(String phone, String ip) {
        if (!isValidPhone(phone)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "手机号格式不正确");
        }

        // 1) 重发间隔
        Boolean cooling = redis().hasKey(k(COOLDOWN_KEY + phone));
        if (Boolean.TRUE.equals(cooling)) {
            Long ttl = redis().getExpire(k(COOLDOWN_KEY + phone), TimeUnit.SECONDS);
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "请 " + (ttl == null ? COOLDOWN_SECONDS : ttl) + " 秒后再试");
        }

        // 2) 手机号每日上限
        String phoneKey = PHONE_DAILY_KEY + phone;
        Long phoneCount = redis().opsForValue().increment(k(phoneKey));
        if (phoneCount != null && phoneCount == 1L) {
            redis().expire(k(phoneKey), Duration.ofDays(1));
        }
        if (phoneCount != null && phoneCount > PHONE_DAILY_LIMIT) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该手机号今日验证码次数已达上限");
        }

        // 3) IP 每日上限（防脚本刷短信）
        if (StringUtils.hasText(ip)) {
            String ipKey = IP_DAILY_KEY + ip;
            Long ipCount = redis().opsForValue().increment(k(ipKey));
            if (ipCount != null && ipCount == 1L) {
                redis().expire(k(ipKey), Duration.ofDays(1));
            }
            if (ipCount != null && ipCount > IP_DAILY_LIMIT) {
                log.warn("sms ip limit exceeded ip={} count={}", ip, ipCount);
                throw new BusinessException(ResultCode.BAD_REQUEST, "操作过于频繁，请稍后再试");
            }
        }

        // 4) 生成并发送
        String code = String.format("%06d", RANDOM.nextInt(1000000));
        boolean sent = smsProvider.send(phone, code);
        if (!sent) {
            throw new BusinessException(ResultCode.ERROR, "短信发送失败，请稍后重试");
        }

        redis().opsForValue().set(k(CODE_KEY + phone), code, CODE_TTL);
        redis().delete(k(FAIL_KEY + phone));
        redis().opsForValue().set(k(COOLDOWN_KEY + phone), "1", Duration.ofSeconds(COOLDOWN_SECONDS));
        log.info("sms code sent phone={} channel={}", mask(phone), smsProvider.channel());
    }

    /**
     * 校验验证码。校验通过后立即作废，避免重复使用。
     */
    public void verify(String phone, String code) {
        if (!isValidPhone(phone)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "手机号格式不正确");
        }
        if (!StringUtils.hasText(code)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请输入验证码");
        }

        String failKey = FAIL_KEY + phone;
        String failCountStr = redis().opsForValue().get(k(failKey));
        int failCount = failCountStr == null ? 0 : Integer.parseInt(failCountStr);
        if (failCount >= MAX_VERIFY_FAIL) {
            redis().delete(k(CODE_KEY + phone));
            throw new BusinessException(ResultCode.BAD_REQUEST, "验证码已作废，请重新获取");
        }

        String cached = redis().opsForValue().get(k(CODE_KEY + phone));
        if (cached == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "验证码已过期，请重新获取");
        }
        if (!cached.equals(code)) {
            Long next = redis().opsForValue().increment(k(failKey));
            if (next != null && next == 1L) {
                redis().expire(k(failKey), CODE_TTL);
            }
            int remain = MAX_VERIFY_FAIL - (next == null ? 1 : next.intValue());
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    remain > 0 ? "验证码错误，还可尝试 " + remain + " 次" : "验证码错误次数过多，请重新获取");
        }

        // 校验通过：作废验证码与失败计数
        redis().delete(k(CODE_KEY + phone));
        redis().delete(k(failKey));
    }

    private String mask(String phone) {
        return phone == null || phone.length() < 7 ? phone
                : phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}


