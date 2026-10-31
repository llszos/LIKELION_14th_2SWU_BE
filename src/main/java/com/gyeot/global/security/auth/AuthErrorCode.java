package com.gyeot.global.security.auth;

import org.springframework.http.HttpStatus;

public enum AuthErrorCode {
    REFRESH_TOKEN_NOT_FOUND(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_NOT_FOUND", "Refresh Token을 찾을 수 없습니다."),
    REFRESH_TOKEN_REVOKED(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_REVOKED", "폐기된 Refresh Token입니다."),
    REFRESH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_EXPIRED", "만료된 Refresh Token입니다."),
    REFRESH_TOKEN_ALREADY_USED(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_ALREADY_USED", "이미 사용된 Refresh Token입니다."),
    USER_NOT_ACTIVE(HttpStatus.UNAUTHORIZED, "USER_NOT_ACTIVE", "사용할 수 없는 계정입니다."),
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "이메일 또는 비밀번호가 올바르지 않습니다."),
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "이미 가입된 이메일입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    AuthErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public String getMessage() { return message; }
}
