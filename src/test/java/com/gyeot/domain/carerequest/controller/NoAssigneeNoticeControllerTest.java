package com.gyeot.domain.carerequest.controller;

import com.gyeot.domain.carerequest.dto.response.NoAssigneeNoticesResponse;
import com.gyeot.domain.carerequest.dto.response.NoAssigneeNoticesResponse.Item;
import com.gyeot.domain.carerequest.dto.response.NoAssigneeNoticesResponse.Target;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.NoAssigneeReason;
import com.gyeot.domain.carerequest.enums.RequestType;
import com.gyeot.domain.carerequest.exception.CareRequestErrorCode;
import com.gyeot.domain.carerequest.service.NoAssigneeNoticeService;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP 요청, 응답 모양만 확인합니다. */
public class NoAssigneeNoticeControllerTest {

    private static final Long USER_ID = 10L;

    private NoAssigneeNoticeService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(NoAssigneeNoticeService.class);
        mvc = MockMvcBuilders.standaloneSetup(new NoAssigneeNoticeController(service))
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
    void 목록은_명세_모양대로_내려간다() throws Exception {
        when(service.getNotices(USER_ID)).thenReturn(new NoAssigneeNoticesResponse(List.of(
                new Item(21L, RequestType.GENERAL, HelpType.HOSPITAL, LocalDateTime.of(2026, 11, 10, 14, 0),
                        new Target(7L, null), NoAssigneeReason.UNAVAILABLE))));

        mvc.perform(get("/api/care-requests/no-assignee-notices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].requestId").value(21))
                .andExpect(jsonPath("$.data.items[0].requestType").value("GENERAL"))
                .andExpect(jsonPath("$.data.items[0].startsAt").value("2026-11-10T14:00:00"))
                .andExpect(jsonPath("$.data.items[0].target.userId").value(7))
                .andExpect(jsonPath("$.data.items[0].reason").value("UNAVAILABLE"));
    }

    @Test
    void 목록이_없으면_빈_배열이다() throws Exception {
        when(service.getNotices(USER_ID)).thenReturn(new NoAssigneeNoticesResponse(List.of()));

        mvc.perform(get("/api/care-requests/no-assignee-notices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void 닫기는_보여_준_사유를_그대로_넘기고_data는_null이다() throws Exception {
        mvc.perform(post("/api/care-requests/21/no-assignee-notice/dismiss")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"DEADLINE_PASSED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(service).dismiss(21L, USER_ID, NoAssigneeReason.DEADLINE_PASSED);
    }

    @Test
    void 사유가_없거나_잘못되면_400이고_서비스를_부르지_않는다() throws Exception {
        mvc.perform(post("/api/care-requests/21/no-assignee-notice/dismiss")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(post("/api/care-requests/21/no-assignee-notice/dismiss")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"BUSY\"}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).dismiss(any(), any(), any());
    }

    @Test
    void 내_요청이_아니면_404다() throws Exception {
        doThrow(new BusinessException(CareRequestErrorCode.REQUEST_NOT_FOUND))
                .when(service).dismiss(21L, USER_ID, NoAssigneeReason.UNAVAILABLE);

        mvc.perform(post("/api/care-requests/21/no-assignee-notice/dismiss")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"UNAVAILABLE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REQUEST_NOT_FOUND"));
    }
}
