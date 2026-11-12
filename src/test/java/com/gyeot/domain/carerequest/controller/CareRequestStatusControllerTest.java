package com.gyeot.domain.carerequest.controller;

import com.gyeot.domain.carerequest.dto.response.CancelResponse;
import com.gyeot.domain.carerequest.dto.response.RequestStatusResponse;
import com.gyeot.domain.carerequest.enums.CancelReason;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.exception.CareRequestErrorCode;
import com.gyeot.domain.carerequest.service.CareRequestStatusService;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP 요청·응답 모양(로그인 사용자, message, 에러 응답)만 확인합니다. */

public class CareRequestStatusControllerTest {
    private static final Long USER_ID = 10L;

    private CareRequestStatusService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(CareRequestStatusService.class);
        mvc = MockMvcBuilders.standaloneSetup(new CareRequestStatusController(service))
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
    void 시작() throws Exception {
        when(service.start(21L, USER_ID)).thenReturn(new RequestStatusResponse(21L, CareRequestStatus.IN_PROGRESS));

        mvc.perform(post("/api/care-requests/21/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("돌봄을 시작했어요."))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
    }

    @Test
    void 완료() throws Exception {
        when(service.complete(21L, USER_ID)).thenReturn(new RequestStatusResponse(21L, CareRequestStatus.COMPLETED));

        mvc.perform(post("/api/care-requests/21/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("요청을 완료했어요."))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    void 취소() throws Exception {
        when(service.cancel(21L, USER_ID)).thenReturn(
                new CancelResponse(21L, CareRequestStatus.CANCELED, CancelReason.REQUESTER_CANCELED));

        mvc.perform(post("/api/care-requests/21/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("요청을 취소했어요."))
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.cancelReason").value("REQUESTER_CANCELED"));
    }

    @Test
    void 권한이_없으면_403_상태가_안_맞으면_409다() throws Exception {
        when(service.cancel(21L, USER_ID)).thenThrow(new BusinessException(CommonErrorCode.FORBIDDEN));
        when(service.complete(21L, USER_ID)).thenThrow(new BusinessException(CareRequestErrorCode.REQUEST_NOT_STARTED));

        mvc.perform(post("/api/care-requests/21/cancel"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/care-requests/21/complete"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_NOT_STARTED"));
    }
}
