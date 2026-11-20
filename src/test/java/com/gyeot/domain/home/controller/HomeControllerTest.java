package com.gyeot.domain.home.controller;

import com.gyeot.domain.access.query.SharingCountReader;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.RequestType;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.*;
import com.gyeot.domain.carerequest.query.RequestDisplayStatus;
import com.gyeot.domain.home.service.HomeService;
import com.gyeot.domain.user.entity.AccountStatus;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.config.ClockConfiguration;
import com.gyeot.global.exception.CommonErrorCode;
import com.gyeot.global.exception.GlobalExceptionHandler;
import com.gyeot.global.security.*;
import com.gyeot.global.security.csrf.RefreshCookieCsrfMatcher;
import com.gyeot.global.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import com.gyeot.domain.user.query.UserProfileQuery;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 보안 필터·Controller·Service와 Mock C/D/User 조회를 연결합니다. 실제 DB/C/D 연동은 아닙니다. */
@WebMvcTest(HomeController.class)
@Import({HomeService.class, ClockConfiguration.class, SecurityConfig.class, PublicApiRequestMatcher.class,
        RefreshCookieCsrfMatcher.class, ApiAuthenticationEntryPoint.class, ApiAccessDeniedHandler.class,
        GlobalExceptionHandler.class})
class HomeControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean CareRequestSummaryReader requests;
    @MockitoBean SharingCountReader sharings;
    @MockitoBean UserProfileQuery profiles;
    @MockitoBean com.gyeot.domain.situation.repository.SituationRepository situations;
    @MockitoBean com.gyeot.domain.situation.repository.SituationParticipantRepository participants;
    @MockitoBean JwtTokenProvider tokens;
    @MockitoBean UserRepository users;
    @MockitoBean com.gyeot.global.security.cookie.RefreshTokenCookieService refreshCookies;

    @Test
    void 미인증_사용자는_401이며_C_D를_조회하지_않는다() throws Exception {
        mvc.perform(get("/api/home")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(requests, sharings);
    }

    @Test
    void 잘못된_토큰은_401이며_C_D를_조회하지_않는다() throws Exception {
        when(tokens.getUserId("invalid")).thenThrow(new IllegalArgumentException("invalid token"));
        mvc.perform(get("/api/home").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(requests, sharings);
    }

    @Test
    void 비활성_사용자는_401이며_C_D를_조회하지_않는다() throws Exception {
        when(tokens.getUserId("inactive")).thenReturn(20L);
        when(users.existsByIdAndAccountStatus(20L, AccountStatus.ACTIVE)).thenReturn(false);
        mvc.perform(get("/api/home").header("Authorization", "Bearer inactive"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(requests, sharings);
    }

    @Test
    void principal_ID로만_C_D를_호출하고_실제_계약_필드를_직렬화한다() throws Exception {
        authenticate();
        var at = LocalDateTime.of(2026, 11, 17, 9, 30, 0, 123456000);
        var needed = new ResponseNeededItem(1L, ResponseNeededType.RECONFIRMATION, RequestType.GENERAL,
                HelpType.HOSPITAL, at, null, 10L, at.minusHours(1));
        var sent = new RequestSummary(2L, RequestType.GENERAL, HelpType.HOSPITAL, at, at.plusHours(1),
                20L, null, RequestDisplayStatus.NO_ASSIGNEE);
        var waiting = new RequestSummary(4L, RequestType.GENERAL, HelpType.HOSPITAL, at, at.plusHours(1),
                20L, 10L, RequestDisplayStatus.WAITING, at.minusHours(1));
        var next = new UpcomingSchedule(3L, HelpType.HOSPITAL, at, null, 10L, 20L);
        when(requests.getHomeOverview(eq(20L), any(LocalDateTime.class)))
                .thenReturn(new HomeRequestOverview(1, 1, 8, List.of(needed), List.of(sent, waiting), List.of(next)));
        when(sharings.countActiveSharings(20L)).thenReturn(3L);
        when(profiles.findProfilesByUserIds(java.util.Set.of(10L)))
                .thenReturn(java.util.Map.of(10L, new UserProfileQuery.UserProfile(10L, null, true)));

        mvc.perform(get("/api/home").header("Authorization", "Bearer valid")
                        .param("userId", "999").param("viewerId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.*", hasSize(6)))
                .andExpect(jsonPath("$.data.counts.*", hasSize(4)))
                .andExpect(jsonPath("$.data.counts.responseNeeded").value(1))
                .andExpect(jsonPath("$.data.counts.unassigned").value(1))
                .andExpect(jsonPath("$.data.counts.upcomingSchedules").value(8))
                .andExpect(jsonPath("$.data.counts.activeSharings").value(3))
                .andExpect(jsonPath("$.data.responseNeededItems[0].*", hasSize(9)))
                .andExpect(jsonPath("$.data.responseNeededItems[0].requestId").value(1))
                .andExpect(jsonPath("$.data.responseNeededItems[0].type").value("RECONFIRMATION"))
                .andExpect(jsonPath("$.data.responseNeededItems[0].requestType").value("GENERAL"))
                .andExpect(jsonPath("$.data.responseNeededItems[0].requesterId").value(10))
                .andExpect(jsonPath("$.data.responseNeededItems[0].helpType").value("HOSPITAL"))
                .andExpect(jsonPath("$.data.responseNeededItems[0].startsAt").value("2026-11-17T09:30:00.123456"))
                .andExpect(jsonPath("$.data.responseNeededItems[0].deadline").value("2026-11-17T08:30:00.123456"))
                .andExpect(jsonPath("$.data.responseNeededItems[0].place").isEmpty())
                .andExpect(jsonPath("$.data.responseNeededItems[0].title").doesNotExist())
                .andExpect(jsonPath("$.data.responseNeededItems[0].requesterName").value("탈퇴한 사용자"))
                .andExpect(jsonPath("$.data.sentOpenItems[0].*", hasSize(10)))
                .andExpect(jsonPath("$.data.sentOpenItems[0].requestId").value(2))
                .andExpect(jsonPath("$.data.sentOpenItems[0].requestType").value("GENERAL"))
                .andExpect(jsonPath("$.data.sentOpenItems[0].helpType").value("HOSPITAL"))
                .andExpect(jsonPath("$.data.sentOpenItems[0].startsAt").value("2026-11-17T09:30:00.123456"))
                .andExpect(jsonPath("$.data.sentOpenItems[0].endsAt").value("2026-11-17T10:30:00.123456"))
                .andExpect(jsonPath("$.data.sentOpenItems[0].requesterId").value(20))
                .andExpect(jsonPath("$.data.sentOpenItems[0].displayStatus").value("NO_ASSIGNEE"))
                .andExpect(jsonPath("$.data.sentOpenItems[0].targetUserId").isEmpty())
                .andExpect(jsonPath("$.data.upcomingSchedules[0].*", hasSize(7)))
                .andExpect(jsonPath("$.data.upcomingSchedules[0].requestId").value(3))
                .andExpect(jsonPath("$.data.upcomingSchedules[0].helpType").value("HOSPITAL"))
                .andExpect(jsonPath("$.data.upcomingSchedules[0].requesterId").value(10))
                .andExpect(jsonPath("$.data.upcomingSchedules[0].place").isEmpty())
                .andExpect(jsonPath("$.data.upcomingSchedules[0].startsAt").value("2026-11-17T09:30:00.123456"))
                .andExpect(jsonPath("$.data.upcomingSchedules[0].assigneeId").value(20))
                .andExpect(jsonPath("$.data.sentOpenItems[0].targetUserName").isEmpty())
                .andExpect(jsonPath("$.data.sentOpenItems[0].responseDeadline").isEmpty())
                .andExpect(jsonPath("$.data.sentOpenItems[1].targetUserName").value("탈퇴한 사용자"))
                .andExpect(jsonPath("$.data.sentOpenItems[1].responseDeadline").value("2026-11-17T08:30:00.123456"))
                .andExpect(jsonPath("$.data.upcomingSchedules[0].counterpartName").value("탈퇴한 사용자"))
                .andExpect(jsonPath("$.data.upcomingSchedules[0].scheduledAt").doesNotExist())
                .andExpect(jsonPath("$.data.situationCards.my").isEmpty())
                .andExpect(jsonPath("$.data.situationCards.participating").isEmpty())
                .andExpect(jsonPath("$.data.isNewUser").value(true));
        verify(requests).getHomeOverview(eq(20L), any(LocalDateTime.class));
        verify(sharings).countActiveSharings(20L);
        verifyNoMoreInteractions(requests, sharings);
    }

    @Test
    void 실제_빈_결과는_건수_0과_빈_배열로_반환한다() throws Exception {
        authenticate();
        when(requests.getHomeOverview(eq(20L), any())).thenReturn(empty());
        when(sharings.countActiveSharings(20L)).thenReturn(0L);
        mvc.perform(get("/api/home").header("Authorization", "Bearer valid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.counts.responseNeeded").value(0))
                .andExpect(jsonPath("$.data.counts.unassigned").value(0))
                .andExpect(jsonPath("$.data.counts.upcomingSchedules").value(0))
                .andExpect(jsonPath("$.data.counts.activeSharings").value(0))
                .andExpect(jsonPath("$.data.responseNeededItems").isEmpty())
                .andExpect(jsonPath("$.data.sentOpenItems").isEmpty())
                .andExpect(jsonPath("$.data.upcomingSchedules").isEmpty());
    }

    @Test
    void C_실패는_오류이며_내부_메시지나_0을_반환하지_않는다() throws Exception {
        authenticate();
        when(requests.getHomeOverview(eq(20L), any())).thenThrow(new IllegalStateException("private C error"));
        assertFailure();
        verifyNoInteractions(sharings);
    }

    @Test
    void C_null_결과도_오류이다() throws Exception {
        authenticate();
        when(requests.getHomeOverview(eq(20L), any())).thenReturn(null);
        assertFailure();
        verifyNoInteractions(sharings);
    }

    @Test
    void D_실패도_공유_0으로_숨기지_않는다() throws Exception {
        authenticate();
        when(requests.getHomeOverview(eq(20L), any())).thenReturn(empty());
        when(sharings.countActiveSharings(20L)).thenThrow(new IllegalStateException("private D error"));
        assertFailure();
    }

    private void assertFailure() throws Exception {
        mvc.perform(get("/api/home").header("Authorization", "Bearer valid"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value(CommonErrorCode.INTERNAL_SERVER_ERROR.getMessage()))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    private void authenticate() {
        when(tokens.getUserId("valid")).thenReturn(20L);
        when(users.existsByIdAndAccountStatus(20L, AccountStatus.ACTIVE)).thenReturn(true);
    }

    private HomeRequestOverview empty() {
        return new HomeRequestOverview(0, 0, 0, List.of(), List.of(), List.of());
    }
}
