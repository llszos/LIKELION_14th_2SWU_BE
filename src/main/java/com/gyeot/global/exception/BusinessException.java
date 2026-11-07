package com.gyeot.global.exception;

import java.util.Objects;

/** 업무 규칙 위반 시 해당 도메인의 ErrorCode와 함께 던지는 예외입니다. */
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(Objects.requireNonNull(errorCode, "errorCode").getMessage());
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
