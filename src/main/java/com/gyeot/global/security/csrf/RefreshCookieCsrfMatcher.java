package com.gyeot.global.security.csrf;

import com.gyeot.global.security.cookie.AuthCookieProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;

@Component
public class RefreshCookieCsrfMatcher implements RequestMatcher {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "TRACE", "OPTIONS");
    private final AuthCookieProperties properties;

    public RefreshCookieCsrfMatcher(AuthCookieProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean matches(HttpServletRequest request) {
        if (SAFE_METHODS.contains(request.getMethod())) return false;
        Cookie[] cookies = request.getCookies();
        return cookies != null && Arrays.stream(cookies)
                .anyMatch(cookie -> properties.getRefreshTokenName().equals(cookie.getName()));
    }
}
