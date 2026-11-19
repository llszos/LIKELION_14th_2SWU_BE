package com.gyeot.domain.carerequest.controller;

import com.gyeot.domain.carerequest.dto.request.ReassignRequest;
import com.gyeot.domain.carerequest.dto.response.ReassignResponse;
import com.gyeot.domain.carerequest.exception.CareRequestErrorCode;
import com.gyeot.domain.carerequest.service.CareRequestReassignService;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP 요청·응답 모양(201, 입력 검증, message, 에러 응답)만 확인합니다. */
public class CareRequestReassignControllerTest {

    private static final Long USER_ID = 10L;
    private static final String VALID_BODY = """
            {"targetUserId": 9, "responseDeadlineHours": 24, "informationIds": [12, 14]}
            """;

    private CareRequestReassignService reassignService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reassignService = mock(CareRequestReassignService.class);
        mvc = MockMvcBuilders.standaloneSetup(new CareRequestReassignController(reassignService))
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
    void 다시_부탁하면_201과_새_지정_정보를_돌려준다() throws Exception {
        when(reassignService.reassign(eq(21L), eq(USER_ID), any(ReassignRequest.class)))
                .thenReturn(new ReassignResponse(21L, 35L, LocalDateTime.of(2026, 11, 9, 18, 0)));

        mvc.perform(post("/api/care-requests/21/assignments").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("다른 분께 다시 부탁했어요."))
                .andExpect(jsonPath("$.data.requestId").value(21))
                .andExpect(jsonPath("$.data.assignmentId").value(35))
                .andExpect(jsonPath("$.data.responseDeadline").value("2026-11-09T18:00:00"));
    }

    @Test
    void 대상을_보내지_않으면_400_INVALID_INPUT이다() throws Exception {
        mvc.perform(post("/api/care-requests/21/assignments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"informationIds\": []}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("요청할 사람을 선택해 주세요."));
        verifyNoInteractions(reassignService);
    }

    @Test
    void 공개_항목을_보내지_않으면_400_INVALID_INPUT이다() throws Exception {
        mvc.perform(post("/api/care-requests/21/assignments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\": 9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(reassignService);
    }

    @Test
    void 담당자_없음이_아니면_409_REQUEST_NOT_REASSIGNABLE이다() throws Exception {
        when(reassignService.reassign(eq(21L), eq(USER_ID), any(ReassignRequest.class)))
                .thenThrow(new BusinessException(CareRequestErrorCode.REQUEST_NOT_REASSIGNABLE));

        mvc.perform(post("/api/care-requests/21/assignments").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.code").value("REQUEST_NOT_REASSIGNABLE"));
    }

    @Test
    void 요청자가_아니면_403이다() throws Exception {
        when(reassignService.reassign(eq(21L), eq(USER_ID), any(ReassignRequest.class)))
                .thenThrow(new BusinessException(CommonErrorCode.FORBIDDEN));

        mvc.perform(post("/api/care-requests/21/assignments").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
