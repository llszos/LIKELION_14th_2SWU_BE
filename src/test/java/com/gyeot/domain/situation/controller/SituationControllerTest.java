package com.gyeot.domain.situation.controller;

import com.gyeot.domain.situation.dto.response.SituationListResponse;
import com.gyeot.domain.situation.dto.response.SituationDetailResponse;
import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.entity.SituationStatus;
import com.gyeot.domain.situation.exception.SituationQueryErrorCode;
import com.gyeot.domain.situation.service.SituationQueryService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MVC 파라미터·인증 ID 전달 테스트입니다. JWT 필터 자체는 기존 Security 테스트 범위입니다. */
class SituationControllerTest {
    private SituationQueryService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(SituationQueryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SituationController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthenticatedUser(20L), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void usesAuthenticatedUserAndDefaultsAndReturnsOnlyBasicFields() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 11, 20, 9, 0);
        var item = new SituationListResponse.Item(1L, "회복", "유형", start, start.plusHours(1), SituationStatus.ACTIVE);
        when(service.listActive(20L, 0, 20))
                .thenReturn(new SituationListResponse(List.of(item), 0, 20, false));

        mvc.perform(get("/api/situations").param("userId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.code").isEmpty())
                .andExpect(jsonPath("$.data.items[0].situationId").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("회복"))
                .andExpect(jsonPath("$.data.items[0].situationType").value("유형"))
                .andExpect(jsonPath("$.data.items[0].startAt").value("2026-11-20T09:00:00"))
                .andExpect(jsonPath("$.data.items[0].endAt").value("2026-11-20T10:00:00"))
                .andExpect(jsonPath("$.data.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.items[0].requestCounts").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].participantCount").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].isOwner").doesNotExist())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        verify(service).listActive(20L, 0, 20);
        verifyNoMoreInteractions(service);
    }

    @Test
    void passesExplicitPagination() throws Exception {
        when(service.listActive(20L, 2, 5)).thenReturn(new SituationListResponse(List.of(), 2, 5, false));
        mvc.perform(get("/api/situations").param("page", "2").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(5));
        verify(service).listActive(20L, 2, 5);
    }

    @Test
    void rejectsMalformedPaginationBeforeCallingService() throws Exception {
        mvc.perform(get("/api/situations").param("page", "invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(service);
    }

    @Test
    void detailUsesAuthenticatedIdAndReturnsOnlyBasicFields() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 11, 20, 9, 0);
        when(service.detailActive(1L, 20L)).thenReturn(new SituationDetailResponse(
                1L, "상세", "RECOVERY", start, start.plusHours(1), SituationStatus.ACTIVE, 10L, 20L, false, null, null, 0, List.of()));

        mvc.perform(get("/api/situations/1").param("userId", "999"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "status":"success", "code":null, "message":null,
                          "data":{
                            "situationId":1, "name":"상세", "situationType":"RECOVERY",
                            "startAt":"2026-11-20T09:00:00", "endAt":"2026-11-20T10:00:00",
                            "status":"ACTIVE", "creatorId":10, "viewerId":20, "isOwner":false, "endedAt":null, "customSituationType":null
                          }
                        }
                        """))
                .andExpect(jsonPath("$.data.requests").isArray())
                .andExpect(jsonPath("$.data.activeRequestCount").value(0))
                .andExpect(jsonPath("$.data.activeParticipantCount").doesNotExist())
                .andExpect(jsonPath("$.data.targetUserName").doesNotExist());
        verify(service).detailActive(1L, 20L);
        verifyNoMoreInteractions(service);
    }

    @Test
    void detailReturnsCommonNotFoundEnvelope() throws Exception {
        when(service.detailActive(1L, 20L))
                .thenThrow(new BusinessException(SituationQueryErrorCode.SITUATION_NOT_FOUND));
        mvc.perform(get("/api/situations/1"))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"status":"error", "code":"SITUATION_NOT_FOUND",
                         "message":"상황을 찾을 수 없습니다.", "data":null}
                        """));
    }

    @Test
    void detailSerializesConfirmedCFieldsWithoutInventedNames() throws Exception {
        var start = LocalDateTime.of(2026, 11, 20, 9, 0);
        var request = new com.gyeot.domain.carerequest.query.CareRequestSummaryReader.RequestSummary(
                8L, com.gyeot.domain.carerequest.enums.RequestType.GENERAL,
                com.gyeot.domain.carerequest.enums.HelpType.HOSPITAL, start, start.plusHours(1), 10L, 30L,
                com.gyeot.domain.carerequest.query.RequestDisplayStatus.NO_ASSIGNEE);
        var situation = Situation.create(10L, "OTHER", "상황", start, start.plusHours(2), "통원 지원");
        when(service.detailActive(1L, 20L))
                .thenReturn(SituationDetailResponse.from(situation, 20L, 4, List.of(request), java.util.Map.of()));

        mvc.perform(get("/api/situations/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.situationType").value("OTHER"))
                .andExpect(jsonPath("$.data.customSituationType").value("통원 지원"))
                .andExpect(jsonPath("$.data.activeRequestCount").value(4))
                .andExpect(jsonPath("$.data.requests[0].requestId").value(8))
                .andExpect(jsonPath("$.data.requests[0].startsAt").value("2026-11-20T09:00:00"))
                .andExpect(jsonPath("$.data.requests[0].endsAt").value("2026-11-20T10:00:00"))
                .andExpect(jsonPath("$.data.requests[0].targetUserId").value(30))
                .andExpect(jsonPath("$.data.requests[0].displayStatus").value("NO_ASSIGNEE"))
                .andExpect(jsonPath("$.data.requests[0].scheduledAt").doesNotExist())
                .andExpect(jsonPath("$.data.requests[0].assigneeId").doesNotExist())
                .andExpect(jsonPath("$.data.requests[0].targetUserName").doesNotExist())
                .andExpect(jsonPath("$.data.isOwner").value(false));
    }

    @Test
    void detailRejectsMalformedIdBeforeCallingService() throws Exception {
        mvc.perform(get("/api/situations/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(service);
    }
}
