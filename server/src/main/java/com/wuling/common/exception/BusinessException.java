package com.wuling.common.exception;

import com.wuling.common.api.ResultCode;
import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(String message) {
        this(ResultCode.ERROR, message);
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
