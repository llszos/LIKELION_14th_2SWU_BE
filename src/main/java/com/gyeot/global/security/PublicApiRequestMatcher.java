package com.gyeot.global.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.DispatcherType;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

import java.util.Set;

/** JWT를 요구하지 않는 공개 API 경로를 인증 설정과 필터가 함께 사용합니다. */
@Component
public class PublicApiRequestMatcher implements RequestMatcher {

    private static final Set<String> PUBLIC_AUTH_PATHS = Set.of(
            "/api/auth/signup",
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/auth/logout",
            "/api/auth/csrf"
    );

    @Override
    public boolean matches(HttpServletRequest request) {
        String path = request.getServletPath();
        String method = request.getMethod();

        if (request.getDispatcherType() == DispatcherType.ERROR
                || HttpMethod.OPTIONS.matches(method)
                || PUBLIC_AUTH_PATHS.contains(path)) {
            return true;
        }

        if (path.equals("/swagger-ui.html")
                || path.startsWith("/swagger-ui/")
                || path.equals("/v3/api-docs")
                || path.startsWith("/v3/api-docs/")) {
            return true;
        }

        return HttpMethod.GET.matches(method)
                && (path.equals("/api/invitations") || path.startsWith("/api/invitations/"));
    }
}
