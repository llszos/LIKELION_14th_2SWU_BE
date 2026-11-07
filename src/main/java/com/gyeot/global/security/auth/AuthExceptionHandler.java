package com.gyeot.global.security.auth;

import com.gyeot.global.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.gyeot.global.security.cookie.RefreshTokenCookieService;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthExceptionHandler {

    private final RefreshTokenCookieService cookieService;

    public AuthExceptionHandler(RefreshTokenCookieService cookieService) {
        this.cookieService = cookieService;
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ApiResponse<Void>> handle(
            AuthException exception,
            HttpServletRequest request
    ) {
        AuthErrorCode code = exception.getErrorCode();
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        boolean refreshRequest = "POST".equalsIgnoreCase(request.getMethod())
                && "/api/auth/refresh".equals(request.getServletPath());
        if (refreshRequest && (code.name().startsWith("REFRESH_TOKEN_")
                || code == AuthErrorCode.USER_NOT_ACTIVE)) {
            cookieService.clearRefreshTokenCookie(headers);
        }
        return ResponseEntity.status(code.getHttpStatus())
                .headers(headers)
                .body(ApiResponse.error(code));
    }
}
