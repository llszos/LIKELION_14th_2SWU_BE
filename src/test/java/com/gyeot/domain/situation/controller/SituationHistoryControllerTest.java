package com.gyeot.domain.situation.controller;

import com.gyeot.domain.situation.dto.response.SituationHistoryListResponse;
import com.gyeot.domain.situation.service.SituationHistoryService;
import com.gyeot.domain.situation.service.SituationHistoryService.Filter;
import com.gyeot.domain.situation.dto.response.SituationHistoryPeopleResponse;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import com.gyeot.global.exception.GlobalExceptionHandler;
import com.gyeot.global.security.auth.AuthenticatedUser;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** JWT 필터는 기존 Security 설정이 담당합니다. 여기서는 인증 사용자 전달과 MVC 계약을 검증합니다. */
class SituationHistoryControllerTest {
    private SituationHistoryService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(SituationHistoryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SituationHistoryController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthenticatedUser(10L), null, List.of()));
    }

    @AfterEach
    void cleanup() { SecurityContextHolder.clearContext(); }

    @Test
    void 인증_사용자와_기본_페이지로_목록을_조회한다() throws Exception {
        when(service.list(10L, 0, 20, Filter.ALL, null)).thenReturn(new SituationHistoryListResponse(List.of(), 0, 20, false));
        mvc.perform(get("/api/situations/history")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        verify(service).list(10L, 0, 20, Filter.ALL, null);
    }

    @Test
    void 명시한_페이지와_크기를_전달한다() throws Exception {
        when(service.list(10L, 1, 100, Filter.HELPED, 20L)).thenReturn(new SituationHistoryListResponse(List.of(), 1, 100, false));
        mvc.perform(get("/api/situations/history").param("page", "1").param("size", "100")
                        .param("filter", "HELPED").param("personId", "20"))
                .andExpect(status().isOk());
        verify(service).list(10L, 1, 100, Filter.HELPED, 20L);
    }

    @Test
    void 사람_후보는_인증_사용자와_선택한_탭으로_조회한다() throws Exception {
        when(service.people(10L, Filter.RECEIVED)).thenReturn(new SituationHistoryPeopleResponse(
                List.of(new SituationHistoryPeopleResponse.Item(20L, "탈퇴한 사용자"))));
        mvc.perform(get("/api/situations/history/people").param("filter", "RECEIVED").param("userId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].userId").value(20))
                .andExpect(jsonPath("$.data.items[0].name").value("탈퇴한 사용자"));
        verify(service).people(10L, Filter.RECEIVED);
    }

    @Test
    void 지원하지_않는_필터는_400이다() throws Exception {
        mvc.perform(get("/api/situations/history").param("filter", "GIVEN"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(service);
    }

    @Test
    void 크기_제한_위반은_400이다() throws Exception {
        when(service.list(10L, 0, 101, Filter.ALL, null)).thenThrow(new BusinessException(CommonErrorCode.INVALID_INPUT));
        mvc.perform(get("/api/situations/history").param("size", "101"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void 권한이_없거나_존재하지_않는_상황은_동일한_404이다() throws Exception {
        when(service.detail(1L, 10L)).thenThrow(new BusinessException(
                com.gyeot.domain.situation.exception.SituationHistoryErrorCode.SITUATION_NOT_FOUND));
        mvc.perform(get("/api/situations/1/history")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SITUATION_NOT_FOUND"))
                .andExpect(jsonPath("$.data").isEmpty());
        verify(service).detail(1L, 10L);
    }
    @Test
    void 목록_HTTP_기간은_명세의_startAt_endAt_시각_필드로_반환한다() throws Exception {
        var start = java.time.LocalDateTime.of(2026, 12, 31, 23, 30);
        var end = java.time.LocalDateTime.of(2027, 1, 1, 0, 0);
        var actual = end.plusMinutes(5);
        var item = new SituationHistoryListResponse.Item(1L, "연말 돌봄", "돌봄", start, end, actual, 2);
        when(service.list(10L, 0, 20, Filter.ALL, null)).thenReturn(
                new SituationHistoryListResponse(List.of(item), 0, 20, false));
        mvc.perform(get("/api/situations/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].startAt").value("2026-12-31T23:30:00"))
                .andExpect(jsonPath("$.data.items[0].endAt").value("2027-01-01T00:00:00"))
                .andExpect(jsonPath("$.data.items[0].endedAt").value("2027-01-01T00:05:00"))
                .andExpect(jsonPath("$.data.items[0].requestCount").value(2))
                .andExpect(jsonPath("$.data.items[0].startDate").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].endDate").doesNotExist());
    }
    @Test
    void 상세는_기본_상황과_C_기록만_시각_손실없이_반환한다() throws Exception {
        var start = java.time.LocalDateTime.of(2026, 12, 31, 23, 59, 59, 123456000);
        var end = java.time.LocalDateTime.of(2027, 1, 1, 0, 0);
        var actual = end.plusMinutes(5);
        var info = new com.gyeot.domain.situation.dto.response.SituationHistoryDetailResponse.Info(
                1L, "연말 돌봄", com.gyeot.domain.situation.entity.SituationStatus.ENDED, actual, start, end, true);
        var record = new com.gyeot.domain.situation.dto.response.SituationHistoryDetailResponse.RequestRecord(
                9L, com.gyeot.domain.carerequest.enums.HelpType.HOSPITAL,
                com.gyeot.domain.carerequest.query.RequestDisplayStatus.COMPLETED, start, end, 20L, "담당자");
        when(service.detail(1L, 10L)).thenReturn(
                new com.gyeot.domain.situation.dto.response.SituationHistoryDetailResponse(info, List.of(record)));
        mvc.perform(get("/api/situations/1/history").param("userId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.situation.*", org.hamcrest.Matchers.hasSize(7)))
                .andExpect(jsonPath("$.data.situation.situationId").value(1))
                .andExpect(jsonPath("$.data.situation.name").value("연말 돌봄"))
                .andExpect(jsonPath("$.data.situation.status").value("ENDED"))
                .andExpect(jsonPath("$.data.situation.startAt").value("2026-12-31T23:59:59.123456"))
                .andExpect(jsonPath("$.data.situation.endAt").value("2027-01-01T00:00:00"))
                .andExpect(jsonPath("$.data.situation.endedAt").value("2027-01-01T00:05:00"))
                .andExpect(jsonPath("$.data.requests[0].*", org.hamcrest.Matchers.hasSize(7)))
                .andExpect(jsonPath("$.data.requests[0].requestId").value(9))
                .andExpect(jsonPath("$.data.requests[0].helpType").value("HOSPITAL"))
                .andExpect(jsonPath("$.data.requests[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.requests[0].startsAt").value("2026-12-31T23:59:59.123456"))
                .andExpect(jsonPath("$.data.requests[0].endsAt").value("2027-01-01T00:00:00"))
                .andExpect(jsonPath("$.data.requests[0].assigneeId").value(20))
                .andExpect(jsonPath("$.data.requests[0].assigneeName").value("담당자"))
                .andExpect(jsonPath("$.data.requests[0].scheduledAt").doesNotExist())
                .andExpect(jsonPath("$.data.requests[0].requesterId").doesNotExist())
                .andExpect(jsonPath("$.data.situation.participantCount").doesNotExist())
                .andExpect(jsonPath("$.data.situation.isOwner").value(true));
        verify(service).detail(1L, 10L);
    }

    @Test
    void 상세의_C_부재나_실패는_빈_성공이_아닌_공통_500이다() throws Exception {
        when(service.detail(1L, 10L)).thenThrow(new IllegalStateException("internal C failure"));
        mvc.perform(get("/api/situations/1/history"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.message").value(CommonErrorCode.INTERNAL_SERVER_ERROR.getMessage()));
    }

    @Test
    void 상세_ID_형식_오류는_400이며_Service를_호출하지_않는다() throws Exception {
        mvc.perform(get("/api/situations/not-a-number/history"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(service);
    }
}
