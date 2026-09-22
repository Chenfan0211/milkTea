package com.wuling.common.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 支付回调签名校验测试 */
class PaymentCallbackSignerTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";  // 32 字节
    private static final String ORDER = "WX202609220103260238";
    private static final String TXN = "TXN123456";

    @Test
    void validSignatureShouldPass() {
        PaymentCallbackSigner signer = PaymentCallbackSigner.of(SECRET);
        long ts = System.currentTimeMillis();
        String sig = signer.sign(ORDER, TXN, ts);
        assertTrue(signer.verify(ORDER, TXN, ts, sig, System.currentTimeMillis()));
    }

    @Test
    void forgedSignatureShouldFail() {
        PaymentCallbackSigner signer = PaymentCallbackSigner.of(SECRET);
        long ts = System.currentTimeMillis();
        assertFalse(signer.verify(ORDER, TXN, ts, "deadbeef", System.currentTimeMillis()));
    }

    @Test
    void tamperedOrderNoShouldFail() {
        PaymentCallbackSigner signer = PaymentCallbackSigner.of(SECRET);
        long ts = System.currentTimeMillis();
        String sig = signer.sign(ORDER, TXN, ts);
        // 换一个订单号，签名不再匹配 —— 防止拿合法签名去刷其他订单
        assertFalse(signer.verify("WX202609220000000000", TXN, ts, sig, System.currentTimeMillis()));
    }

    @Test
    void tamperedTransactionIdShouldFail() {
        PaymentCallbackSigner signer = PaymentCallbackSigner.of(SECRET);
        long ts = System.currentTimeMillis();
        String sig = signer.sign(ORDER, TXN, ts);
        assertFalse(signer.verify(ORDER, "TXN999999", ts, sig, System.currentTimeMillis()));
    }

    @Test
    void wrongSecretShouldFail() {
        PaymentCallbackSigner attacker = PaymentCallbackSigner.of("ffffffffffffffffffffffffffffffff");
        PaymentCallbackSigner server = PaymentCallbackSigner.of(SECRET);
        long ts = System.currentTimeMillis();
        String sig = attacker.sign(ORDER, TXN, ts);
        assertFalse(server.verify(ORDER, TXN, ts, sig, System.currentTimeMillis()),
                "攻击者用自己密钥签的名必须被拒绝");
    }

    @Test
    void expiredTimestampShouldBeRejected() {
        PaymentCallbackSigner signer = PaymentCallbackSigner.of(SECRET);
        long old = System.currentTimeMillis() - 10 * 60 * 1000L;  // 10 分钟前
        String sig = signer.sign(ORDER, TXN, old);
        assertFalse(signer.verify(ORDER, TXN, old, sig, System.currentTimeMillis()),
                "超出 5 分钟窗口的签名必须被拒绝（防重放）");
    }

    @Test
    void futureTimestampBeyondWindowShouldBeRejected() {
        PaymentCallbackSigner signer = PaymentCallbackSigner.of(SECRET);
        long future = System.currentTimeMillis() + 10 * 60 * 1000L;
        String sig = signer.sign(ORDER, TXN, future);
        assertFalse(signer.verify(ORDER, TXN, future, sig, System.currentTimeMillis()));
    }

    @Test
    void nullArgumentsShouldFailSafely() {
        PaymentCallbackSigner signer = PaymentCallbackSigner.of(SECRET);
        long ts = System.currentTimeMillis();
        assertFalse(signer.verify(null, TXN, ts, "abc", ts));
        assertFalse(signer.verify(ORDER, null, ts, "abc", ts));
        assertFalse(signer.verify(ORDER, TXN, ts, null, ts));
    }

    @Test
    void weakSecretShouldBeRejected() {
        assertThrows(IllegalArgumentException.class, () -> PaymentCallbackSigner.of("short"));
        assertThrows(IllegalArgumentException.class, () -> PaymentCallbackSigner.of(null));
    }

    @Test
    void signatureShouldBeCaseInsensitiveAndTrimmed() {
        PaymentCallbackSigner signer = PaymentCallbackSigner.of(SECRET);
        long ts = System.currentTimeMillis();
        String sig = signer.sign(ORDER, TXN, ts);
        assertTrue(signer.verify(ORDER, TXN, ts, "  " + sig.toUpperCase() + "  ", System.currentTimeMillis()));
    }
}
