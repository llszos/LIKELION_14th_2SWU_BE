package com.gyeot.domain.carerequest.controller;

import com.gyeot.domain.carerequest.dto.response.RespondResponse;
import com.gyeot.domain.carerequest.dto.response.WithdrawResponse;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.exception.CareRequestErrorCode;
import com.gyeot.domain.carerequest.service.CareRequestResponseService;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.GlobalExceptionHandler;
import com.gyeot.global.security.auth.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 요청·응답 모양(본문 변환, 로그인 사용자, message, 에러 응답)만 확인합니다. (DB나 Docker 필요 없음)
 * Service는 Mock이라 업무 규칙은 여기서 확인하지 않습니다. (CareRequestResponseServiceTest에서 확인)
 */
public class CareRequestResponseControllerTest {
    private static final Long USER_ID = 20L;

    private CareRequestResponseService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(CareRequestResponseService.class);
        mvc = MockMvcBuilders.standaloneSetup(new CareRequestResponseController(service))
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
    void 수락하면_안내_문구와_함께_200이다() throws Exception {
        when(service.respond(21L, USER_ID, AssignmentResponseStatus.AVAILABLE))
                .thenReturn(new RespondResponse(21L, CareRequestStatus.ACCEPTED, AssignmentResponseStatus.AVAILABLE));

        mvc.perform(post("/api/care-requests/21/respond")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"response\": \"AVAILABLE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("요청을 수락했어요."))
                .andExpect(jsonPath("$.data.requestId").value(21))
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.responseStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.accepted").doesNotExist()); // 내부용 메서드는 응답에 안 나감
    }

    @Test
    void 어려움이면_message가_null이다() throws Exception {
        when(service.respond(21L, USER_ID, AssignmentResponseStatus.UNAVAILABLE))
                .thenReturn(new RespondResponse(21L, CareRequestStatus.REQUESTED, AssignmentResponseStatus.UNAVAILABLE));

        mvc.perform(post("/api/care-requests/21/respond")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"response\": \"UNAVAILABLE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void 응답_값이_없거나_잘못되면_400이고_서비스를_부르지_않는다() throws Exception {
        mvc.perform(post("/api/care-requests/21/respond")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        mvc.perform(post("/api/care-requests/21/respond")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"response\": \"MAYBE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        verify(service, never()).respond(any(), any(), any());
    }

    @Test
    void 서비스의_업무_예외는_에러_코드와_상태로_바뀐다() throws Exception {
        when(service.respond(21L, USER_ID, AssignmentResponseStatus.AVAILABLE))
                .thenThrow(new BusinessException(CareRequestErrorCode.REQUEST_ALREADY_ACCEPTED));

        mvc.perform(post("/api/care-requests/21/respond")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"response\": \"AVAILABLE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.code").value("REQUEST_ALREADY_ACCEPTED"));
    }

    @Test
    void 철회하면_안내_문구와_함께_200이다() throws Exception {
        when(service.withdraw(21L, USER_ID)).thenReturn(new WithdrawResponse(21L, CareRequestStatus.REQUESTED));

        mvc.perform(post("/api/care-requests/21/withdraw"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("수락을 철회했어요."))
                .andExpect(jsonPath("$.data.status").value("REQUESTED"));
    }

}
