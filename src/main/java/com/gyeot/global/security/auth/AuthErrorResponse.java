package com.gyeot.global.security.auth;

public record AuthErrorResponse(String code, String message) {
    public static AuthErrorResponse from(AuthErrorCode code) {
        return new AuthErrorResponse(code.getCode(), code.getMessage());
    }
}
