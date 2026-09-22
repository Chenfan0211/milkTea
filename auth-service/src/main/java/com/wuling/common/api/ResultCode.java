package com.wuling.common.api;

public final class ResultCode {

    public static final int SUCCESS = 0;
    public static final int BAD_REQUEST = 400;
    public static final int FORBIDDEN = 403;
    public static final int NOT_FOUND = 404;
    public static final int ERROR = 500;

    public static final int UNAUTHORIZED = 8888;
    public static final int TOKEN_EXPIRED = 9999;

    private ResultCode() {
    }
}
