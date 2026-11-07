package com.gyeot.global.security.auth;

import com.gyeot.global.exception.BusinessException;

public class AuthException extends BusinessException {

    private final AuthErrorCode errorCode;

    public AuthException(AuthErrorCode errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    public AuthErrorCode getErrorCode() { return errorCode; }
}
