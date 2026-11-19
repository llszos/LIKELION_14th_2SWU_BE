package com.gyeot.domain.situation.controller;

import com.gyeot.domain.situation.dto.response.SituationDetailResponse;
import com.gyeot.domain.situation.dto.response.SituationListResponse;
import com.gyeot.domain.situation.entity.SituationStatus;
import com.gyeot.domain.situation.service.SituationQueryService;
import com.gyeot.domain.user.entity.AccountStatus;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.exception.GlobalExceptionHandler;
import com.gyeot.global.security.*;
import com.gyeot.global.security.csrf.RefreshCookieCsrfMatcher;
import com.gyeot.global.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 기존 standalone MVC 테스트가 다루지 않는 실제 보안 필터 연결만 검증합니다. DB는 사용하지 않습니다. */
@WebMvcTest(SituationController.class)
@Import({SecurityConfig.class, PublicApiRequestMatcher.class, RefreshCookieCsrfMatcher.class,
        ApiAuthenticationEntryPoint.class, ApiAccessDeniedHandler.class, GlobalExceptionHandler.class})
class SituationQuerySecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean SituationQueryService service;
    @MockitoBean JwtTokenProvider tokens;
    @MockitoBean UserRepository users;
    @MockitoBean com.gyeot.global.security.cookie.RefreshTokenCookieService refreshCookies;

    @Test
    void anonymousCannotReachEitherQuery() throws Exception {
        assertUnauthorized(null);
    }

    @Test
    void invalidTokenCannotReachEitherQuery() throws Exception {
        when(tokens.getUserId("invalid")).thenThrow(new IllegalArgumentException("invalid token"));
        assertUnauthorized("invalid");
    }

    @Test
    void tokenForInactiveOrMissingUserCannotReachEitherQuery() throws Exception {
        when(tokens.getUserId("inactive")).thenReturn(20L);
        when(users.existsByIdAndAccountStatus(20L, AccountStatus.ACTIVE)).thenReturn(false);
        assertUnauthorized("inactive");
    }

    @Test
    void validTokenPrincipalReachesBothQueriesIgnoringClientUserId() throws Exception {
        when(tokens.getUserId("valid")).thenReturn(20L);
        when(users.existsByIdAndAccountStatus(20L, AccountStatus.ACTIVE)).thenReturn(true);
        when(service.listActive(20L, 0, 20)).thenReturn(new SituationListResponse(List.of(), 0, 20, false));
        var start = LocalDateTime.of(2026, 11, 20, 9, 0);
        when(service.detailActive(1L, 20L)).thenReturn(new SituationDetailResponse(
                1L, "상황", "유형", start, start.plusHours(1), SituationStatus.ACTIVE, 10L, 20L, false, null, null, 0, List.of()));
        for (String uri : List.of("/api/situations", "/api/situations/1")) {
            mvc.perform(get(uri).header("Authorization", "Bearer valid").param("userId", "999"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("success"));
        }
        verify(service).listActive(20L, 0, 20);
        verify(service).detailActive(1L, 20L);
        verifyNoMoreInteractions(service);
    }

    private void assertUnauthorized(String token) throws Exception {
        for (String uri : List.of("/api/situations", "/api/situations/1")) {
            var request = get(uri);
            if (token != null) request.header("Authorization", "Bearer " + token);
            mvc.perform(request).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value("error"))
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
        verifyNoInteractions(service);
    }
}
