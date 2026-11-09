package com.gyeot.global.security.auth;

import com.gyeot.global.security.cookie.AuthCookieProperties;
import com.gyeot.global.security.cookie.RefreshTokenCookieService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthExceptionHandlerTest {

    @Test
    void refreshFailureUsesCommonErrorAndExpiresRefreshCookie() {
        AuthCookieProperties properties = new AuthCookieProperties();
        AuthExceptionHandler handler = new AuthExceptionHandler(
                new RefreshTokenCookieService(properties, Clock.systemUTC()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
        request.setServletPath("/api/auth/refresh");

        var response = handler.handle(
                new AuthException(AuthErrorCode.REFRESH_TOKEN_EXPIRED), request);

        assertEquals(401, response.getStatusCode().value());
        assertEquals("error", response.getBody().status());
        assertEquals("REFRESH_TOKEN_EXPIRED", response.getBody().code());
        String cookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertTrue(cookie.contains("Max-Age=0"));
        assertTrue(cookie.contains("refresh_token="));
    }

    @Test
    void inactiveUserOnRefreshAlsoExpiresCookie() {
        AuthCookieProperties properties = new AuthCookieProperties();
        AuthExceptionHandler handler = new AuthExceptionHandler(
                new RefreshTokenCookieService(properties, Clock.systemUTC()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
        request.setServletPath("/api/auth/refresh");

        var response = handler.handle(new AuthException(AuthErrorCode.USER_NOT_ACTIVE), request);

        assertTrue(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE).contains("Max-Age=0"));
    }

    @Test
    void loginFailureDoesNotExpireRefreshCookie() {
        AuthCookieProperties properties = new AuthCookieProperties();
        AuthExceptionHandler handler = new AuthExceptionHandler(
                new RefreshTokenCookieService(properties, Clock.systemUTC()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setServletPath("/api/auth/login");

        var response = handler.handle(new AuthException(AuthErrorCode.LOGIN_FAILED), request);

        assertEquals(null, response.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
    }
}
