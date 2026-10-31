package com.gyeot.global.security.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.gyeot.global.security.cookie.RefreshTokenCookieService;
import org.springframework.http.HttpHeaders;

@RestControllerAdvice
public class AuthExceptionHandler {

    private final RefreshTokenCookieService cookieService;

    public AuthExceptionHandler(RefreshTokenCookieService cookieService) {
        this.cookieService = cookieService;
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<AuthErrorResponse> handle(AuthException exception) {
        AuthErrorCode code = exception.getErrorCode();
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        if (code.name().startsWith("REFRESH_TOKEN_")) {
            cookieService.clearRefreshTokenCookie(headers);
        }
        return ResponseEntity.status(code.getStatus()).headers(headers).body(AuthErrorResponse.from(code));
    }
}
