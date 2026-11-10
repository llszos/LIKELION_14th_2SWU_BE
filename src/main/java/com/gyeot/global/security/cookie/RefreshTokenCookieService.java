package com.gyeot.global.security.cookie;

import com.gyeot.global.security.auth.AuthErrorCode;
import com.gyeot.global.security.auth.AuthException;
import com.gyeot.global.security.refresh.service.IssuedRefreshToken;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

@Component
public class RefreshTokenCookieService {

    private final AuthCookieProperties properties;
    private final Clock clock;

    public RefreshTokenCookieService(AuthCookieProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void addRefreshTokenCookie(HttpHeaders headers, IssuedRefreshToken token) {
        Duration remaining = Duration.between(LocalDateTime.now(clock), token.expiresAt());
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(properties.getRefreshTokenName(), token.rawToken())
                .httpOnly(true)
                .secure(properties.isSecure())
                .sameSite(properties.getSameSite())
                .path(properties.getPath())
                .maxAge(remaining.isNegative() ? Duration.ZERO : remaining);
        if (properties.getDomain() != null && !properties.getDomain().isBlank()) {
            builder.domain(properties.getDomain());
        }
        headers.add(HttpHeaders.SET_COOKIE, builder.build().toString());
    }

    public void clearRefreshTokenCookie(HttpHeaders headers) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(properties.getRefreshTokenName(), "")
                .httpOnly(true)
                .secure(properties.isSecure())
                .sameSite(properties.getSameSite())
                .path(properties.getPath())
                .maxAge(Duration.ZERO);
        if (properties.getDomain() != null && !properties.getDomain().isBlank()) {
            builder.domain(properties.getDomain());
        }
        headers.add(HttpHeaders.SET_COOKIE, builder.build().toString());
    }

    public String readRefreshToken(HttpServletRequest request) {
        return readRefreshTokenIfPresent(request)
                .orElseThrow(() -> new AuthException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND));
    }

    public java.util.Optional<String> readRefreshTokenIfPresent(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (properties.getRefreshTokenName().equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                    return java.util.Optional.of(cookie.getValue());
                }
            }
        }
        return java.util.Optional.empty();
    }
}
