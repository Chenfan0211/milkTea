package com.wuling.user.tongue;

/**
 * 舌象第三方客户端异常。
 *
 * <p>异常消息只包含可对用户展示的概述，不携带第三方原始 body、图片或密钥。
 */
public class TongueClientException extends RuntimeException {

    public TongueClientException(String message) {
        super(message);
    }

    public TongueClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
