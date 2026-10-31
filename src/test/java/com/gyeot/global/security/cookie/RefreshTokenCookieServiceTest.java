package com.gyeot.global.security.cookie;

import com.gyeot.global.security.refresh.service.IssuedRefreshToken;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RefreshTokenCookieServiceTest {

    @Test
    void setsHttpOnlyCookieForRemainingLifetimeAndCanClearIt() {
        AuthCookieProperties properties = new AuthCookieProperties();
        properties.setSecure(true);
        properties.setSameSite("None");
        properties.setPath("/api/auth");
        properties.setDomain("example.com");
        Clock clock = Clock.fixed(java.time.Instant.parse("2026-10-30T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        RefreshTokenCookieService service = new RefreshTokenCookieService(properties, clock);
        LocalDateTime now = LocalDateTime.now(clock);
        HttpHeaders headers = new HttpHeaders();

        service.addRefreshTokenCookie(headers,
                new IssuedRefreshToken(1L, "raw-token", "session", now.plusDays(3)));
        String cookie = headers.getFirst(HttpHeaders.SET_COOKIE);
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("SameSite=None"));
        assertTrue(cookie.contains("Path=/api/auth"));
        assertTrue(cookie.contains("Domain=example.com"));
        assertTrue(cookie.contains("Max-Age=259200"));

        service.clearRefreshTokenCookie(headers);
        assertTrue(headers.get(HttpHeaders.SET_COOKIE).get(1).contains("Max-Age=0"));
    }
}
