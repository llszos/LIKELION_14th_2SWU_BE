package com.gyeot.domain.situation.controller;

import com.gyeot.domain.situation.dto.response.SituationEndResponse;
import com.gyeot.domain.situation.entity.SituationStatus;
import com.gyeot.domain.situation.exception.SituationErrorCode;
import com.gyeot.domain.situation.service.SituationEndService;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.GlobalExceptionHandler;
import com.gyeot.global.security.auth.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 기존 MVC 테스트 패턴. JWT 필터 자체는 이 테스트의 검증 범위가 아닙니다. */
class SituationEndControllerTest {
    private SituationEndService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(SituationEndService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SituationEndController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthenticatedUser(10L), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 인증_사용자_ID로_종료하고_공통_응답을_반환한다() throws Exception {
        when(service.end(1L, 10L)).thenReturn(new SituationEndResponse(1L, SituationStatus.ENDED,
                LocalDateTime.of(2026, 11, 12, 12, 0)));
        mvc.perform(post("/api/situations/1/end"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.code").isEmpty())
                .andExpect(jsonPath("$.message").isEmpty())
                .andExpect(jsonPath("$.data.situationId").value(1))
                .andExpect(jsonPath("$.data.status").value("ENDED"))
                .andExpect(jsonPath("$.data.endedAt").value("2026-11-12T12:00:00"));
        verify(service).end(1L, 10L);
    }

    @Test
    void 비생성자는_403_코드를_반환한다() throws Exception {
        assertError(SituationErrorCode.SITUATION_FORBIDDEN, 403);
    }

    @Test
    void 상황_부재나_접근_불가는_404_코드를_반환한다() throws Exception {
        assertError(SituationErrorCode.SITUATION_NOT_FOUND, 404);
    }

    @Test
    void 종료된_상황은_409_코드를_반환한다() throws Exception {
        assertError(SituationErrorCode.SITUATION_ALREADY_ENDED, 409);
    }

    @Test
    void 잘못된_상황_ID_형식은_400이다() throws Exception {
        mvc.perform(post("/api/situations/invalid/end")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(service);
    }

    private void assertError(SituationErrorCode code, int httpStatus) throws Exception {
        when(service.end(1L, 10L)).thenThrow(new BusinessException(code));
        mvc.perform(post("/api/situations/1/end"))
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.code").value(code.name()))
                .andExpect(jsonPath("$.message").value(code.getMessage()))
                .andExpect(jsonPath("$.data").isEmpty());
    }
}
