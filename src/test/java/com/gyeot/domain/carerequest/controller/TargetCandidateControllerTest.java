package com.gyeot.domain.carerequest.controller;

import com.gyeot.domain.carerequest.dto.response.TargetCandidatesResponse;
import com.gyeot.domain.carerequest.dto.response.TargetCandidatesResponse.CandidateView;
import com.gyeot.domain.carerequest.exception.CareRequestErrorCode;
import com.gyeot.domain.carerequest.service.CareRequestCreateService;
import com.gyeot.domain.carerequest.service.CareRequestDetailService;
import com.gyeot.domain.carerequest.service.TargetCandidateService;
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

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP 요청·응답 모양(쿼리 파라미터, 응답 필드, 에러 응답, 요청 상세와 경로 구분)만 확인합니다. */
public class TargetCandidateControllerTest {

    private static final Long USER_ID = 10L;

    private TargetCandidateService candidateService;
    private CareRequestDetailService detailService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        candidateService = mock(TargetCandidateService.class);
        detailService = mock(CareRequestDetailService.class);
        // 요청 상세(GET /{requestId})와 함께 등록해 경로가 겹치지 않는지 확인
        mvc = MockMvcBuilders.standaloneSetup(new TargetCandidateController(candidateService),
                        new CareRequestController(mock(CareRequestCreateService.class), detailService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthenticatedUser(USER_ID), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 상황_ID로_후보를_조회하면_보호자_먼저_후보_목록을_돌려준다() throws Exception {
        when(candidateService.findCandidates(USER_ID, 3L, null)).thenReturn(new TargetCandidatesResponse(List.of(
                new CandidateView(7L, "박민재", true), new CandidateView(9L, "이서연", false))));

        mvc.perform(get("/api/care-requests/target-candidates").param("situationId", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.items[0].userId").value(7))
                .andExpect(jsonPath("$.data.items[0].name").value("박민재"))
                .andExpect(jsonPath("$.data.items[0].isGuardian").value(true))
                .andExpect(jsonPath("$.data.items[1].isGuardian").value(false));
        verifyNoInteractions(detailService); // 요청 상세로 잘못 가지 않음
    }

    @Test
    void 다시_부탁하기는_요청_ID만_보내도_된다() throws Exception {
        when(candidateService.findCandidates(USER_ID, null, 21L)).thenReturn(new TargetCandidatesResponse(List.of()));

        mvc.perform(get("/api/care-requests/target-candidates").param("requestId", "21"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void 종료된_상황이면_409_REQUEST_SITUATION_ENDED다() throws Exception {
        when(candidateService.findCandidates(USER_ID, 3L, null))
                .thenThrow(new BusinessException(CareRequestErrorCode.REQUEST_SITUATION_ENDED));

        mvc.perform(get("/api/care-requests/target-candidates").param("situationId", "3"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_SITUATION_ENDED"));
    }

    @Test
    void 숫자가_아닌_상황_ID는_400_INVALID_INPUT이다() throws Exception {
        mvc.perform(get("/api/care-requests/target-candidates").param("situationId", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(candidateService);
    }
}
