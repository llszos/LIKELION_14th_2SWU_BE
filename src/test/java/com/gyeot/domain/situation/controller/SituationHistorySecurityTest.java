package com.gyeot.domain.situation.controller;

import com.gyeot.domain.carerequest.query.CareRequestSummaryReader;
import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.repository.SituationHistoryQueryRepository;
import com.gyeot.domain.situation.service.SituationHistoryService;
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
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 보안 필터와 B Service를 연결합니다. Repository와 C는 Mock이며 실제 DB/C 검증은 아닙니다. */
@WebMvcTest(SituationHistoryController.class)
@Import({SecurityConfig.class, PublicApiRequestMatcher.class, RefreshCookieCsrfMatcher.class,
        ApiAuthenticationEntryPoint.class, ApiAccessDeniedHandler.class, GlobalExceptionHandler.class,
        SituationHistoryService.class})
class SituationHistorySecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtTokenProvider tokens;
    @MockitoBean UserRepository users;
    @MockitoBean SituationHistoryQueryRepository repository;
    @MockitoBean CareRequestSummaryReader reader;
    @MockitoBean com.gyeot.global.security.cookie.RefreshTokenCookieService refreshCookies;

    @Test
    void 미인증_요청은_401이며_B와_C를_조회하지_않는다() throws Exception {
        mvc.perform(get("/api/situations/1/history"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(repository, reader);
    }

    @Test
    void 잘못된_토큰은_401이며_B와_C를_조회하지_않는다() throws Exception {
        when(tokens.getUserId("invalid")).thenThrow(new IllegalArgumentException("invalid token"));
        mvc.perform(get("/api/situations/1/history").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(repository, reader);
    }

    @Test
    void 비활성_사용자는_401이며_B와_C를_조회하지_않는다() throws Exception {
        when(tokens.getUserId("inactive")).thenReturn(20L);
        when(users.existsByIdAndAccountStatus(20L, AccountStatus.ACTIVE)).thenReturn(false);
        mvc.perform(get("/api/situations/1/history").header("Authorization", "Bearer inactive"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(repository, reader);
    }

    @Test
    void 실제_로그인_참여자_ID로_권한과_C를_조회하며_사용자_입력_ID는_무시한다() throws Exception {
        authenticate();
        when(repository.findAccessibleHistory(1L, 20L)).thenReturn(Optional.of(ended()));
        when(reader.getPastRequestRecords(1L, 20L)).thenReturn(List.of());
        mvc.perform(get("/api/situations/1/history").header("Authorization", "Bearer valid")
                        .param("userId", "999").param("viewerId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.situation.status").value("ENDED"))
                .andExpect(jsonPath("$.data.situation.startAt").value("2026-12-31T23:30:00"))
                .andExpect(jsonPath("$.data.situation.endAt").value("2027-01-01T00:00:00"))
                .andExpect(jsonPath("$.data.requests").isEmpty());
        var order = inOrder(repository, reader);
        order.verify(repository).findAccessibleHistory(1L, 20L);
        order.verify(reader).getPastRequestRecords(1L, 20L);
        verifyNoMoreInteractions(repository, reader);
    }

    @Test
    void 없는_상황과_권한없는_조회는_404이며_C를_호출하지_않는다() throws Exception {
        authenticate();
        when(repository.findAccessibleHistory(1L, 20L)).thenReturn(Optional.empty());
        when(repository.findAccessibleHistory(2L, 20L)).thenReturn(Optional.empty());
        for (long id : new long[]{1L, 2L}) {
            mvc.perform(get("/api/situations/" + id + "/history").header("Authorization", "Bearer valid"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("SITUATION_NOT_FOUND"))
                    .andExpect(jsonPath("$.data").isEmpty());
        }
        verifyNoInteractions(reader);
    }

    @Test
    void C_조회_실패는_내부_메시지나_빈_성공을_반환하지_않는다() throws Exception {
        authenticate();
        when(repository.findAccessibleHistory(1L, 20L)).thenReturn(Optional.of(ended()));
        when(reader.getPastRequestRecords(1L, 20L)).thenThrow(new IllegalStateException("private C error"));
        mvc.perform(get("/api/situations/1/history").header("Authorization", "Bearer valid"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value(
                        com.gyeot.global.exception.CommonErrorCode.INTERNAL_SERVER_ERROR.getMessage()))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    private void authenticate() {
        when(tokens.getUserId("valid")).thenReturn(20L);
        when(users.existsByIdAndAccountStatus(20L, AccountStatus.ACTIVE)).thenReturn(true);
    }

    private Situation ended() {
        var start = LocalDateTime.of(2026, 12, 31, 23, 30);
        Situation situation = Situation.create(10L, "돌봄", "지난 돌봄", start, start.plusMinutes(30));
        situation.end(start.plusMinutes(35));
        return situation;
    }
}
