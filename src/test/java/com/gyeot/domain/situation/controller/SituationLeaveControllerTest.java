package com.gyeot.domain.situation.controller;

import com.gyeot.domain.situation.dto.response.SituationLeaveResponse;
import com.gyeot.domain.situation.entity.SituationParticipantStatus;
import com.gyeot.domain.situation.exception.SituationLeaveErrorCode;
import com.gyeot.domain.situation.service.SituationLeaveProcessor;
import com.gyeot.domain.user.entity.AccountStatus;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
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

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 SecurityConfig 필터 체인으로 인증 및 공통 HTTP 응답을 검증합니다. */
@WebMvcTest(SituationLeaveController.class)
@Import({SecurityConfig.class, PublicApiRequestMatcher.class, RefreshCookieCsrfMatcher.class,
        ApiAuthenticationEntryPoint.class, ApiAccessDeniedHandler.class, GlobalExceptionHandler.class})
class SituationLeaveControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SituationLeaveProcessor processor;
    @MockitoBean JwtTokenProvider tokens;
    @MockitoBean UserRepository users;
    @MockitoBean com.gyeot.global.security.cookie.RefreshTokenCookieService refreshCookies;

    private void authenticate() {
        when(tokens.getUserId("valid-token")).thenReturn(2L);
        when(users.existsByIdAndAccountStatus(2L, AccountStatus.ACTIVE)).thenReturn(true);
    }

    @Test
    void usesAuthenticatedUserAndReturnsCommonSuccess() throws Exception {
        authenticate();
        var leftAt = LocalDateTime.of(2026, 11, 15, 12, 0);
        when(processor.leave(3L, 2L)).thenReturn(
                new SituationLeaveResponse(3L, 2L, SituationParticipantStatus.LEFT, leftAt));
        mvc.perform(post("/api/situations/3/leave").header("Authorization", "Bearer valid-token")
                        .param("userId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.situationId").value(3))
                .andExpect(jsonPath("$.data.userId").value(2))
                .andExpect(jsonPath("$.data.status").value("LEFT"))
                .andExpect(jsonPath("$.data.leftAt").value("2026-11-15T12:00:00"));
        verify(processor).leave(3L, 2L);
        verifyNoMoreInteractions(processor);
    }

    @Test
    void anonymousIsUnauthorizedWithoutCallingProcessor() throws Exception {
        mvc.perform(post("/api/situations/3/leave"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(processor);
    }

    @Test
    void invalidTokenIsUnauthorized() throws Exception {
        when(tokens.getUserId("invalid")).thenThrow(new IllegalArgumentException("invalid"));
        mvc.perform(post("/api/situations/3/leave").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(processor);
    }

    @Test
    void ownerReturnsForbidden() throws Exception {
        authenticate();
        when(processor.leave(3L, 2L)).thenThrow(new BusinessException(CommonErrorCode.FORBIDDEN));
        mvc.perform(post("/api/situations/3/leave").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void endedReturnsConflict() throws Exception {
        authenticate();
        when(processor.leave(3L, 2L)).thenThrow(new BusinessException(SituationLeaveErrorCode.SITUATION_ENDED));
        mvc.perform(post("/api/situations/3/leave").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SITUATION_ENDED"));
    }

    @Test
    void unrelatedUserReturnsNotFound() throws Exception {
        authenticate();
        when(processor.leave(3L, 2L)).thenThrow(new BusinessException(CommonErrorCode.NOT_FOUND));
        mvc.perform(post("/api/situations/3/leave").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
