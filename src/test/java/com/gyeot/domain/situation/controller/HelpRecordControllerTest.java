package com.gyeot.domain.situation.controller;

import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.*;
import com.gyeot.domain.carerequest.query.RequestDisplayStatus;
import com.gyeot.domain.situation.service.HelpRecordQueryService;
import com.gyeot.domain.user.entity.AccountStatus;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.exception.GlobalExceptionHandler;
import com.gyeot.global.security.*;
import com.gyeot.global.security.csrf.RefreshCookieCsrfMatcher;
import com.gyeot.global.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.LocalDateTime;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 SecurityConfig + 실제 B Service, C Reader만 mock. DB 없이 인증·HTTP 계약을 검증합니다. */
@WebMvcTest(HelpRecordController.class)
@Import({HelpRecordQueryService.class, SecurityConfig.class, PublicApiRequestMatcher.class,
        RefreshCookieCsrfMatcher.class, ApiAuthenticationEntryPoint.class, ApiAccessDeniedHandler.class,
        GlobalExceptionHandler.class})
class HelpRecordControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean CareRequestSummaryReader reader;
    @MockitoBean JwtTokenProvider tokens;
    @MockitoBean UserRepository users;
    @MockitoBean com.gyeot.global.security.cookie.RefreshTokenCookieService refreshCookies;

    private void authenticate() {
        when(tokens.getUserId("valid-token")).thenReturn(10L);
        when(users.existsByIdAndAccountStatus(10L, AccountStatus.ACTIVE)).thenReturn(true);
    }

    @Test void defaultsAndUserIdSpoofingStillQueryOnlyAuthenticatedUser() throws Exception {
        authenticate();
        var page = PageRequest.of(0, 20);
        when(reader.getHelpRecords(10L, HelpRecordFilter.ALL, null, page))
                .thenReturn(new PageImpl<>(List.of(), page, 0));
        mvc.perform(get("/api/help-records").header("Authorization", "Bearer valid-token").param("userId", "999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20)).andExpect(jsonPath("$.data.hasNext").value(false));
        verify(reader).getHelpRecords(10L, HelpRecordFilter.ALL, null, page);
        verifyNoMoreInteractions(reader);
    }

    @ParameterizedTest @ValueSource(strings = {"ALL", "GIVEN", "RECEIVED"})
    void bindsFiltersPersonAndPagination(String type) throws Exception {
        authenticate();
        var page = PageRequest.of(2, 3);
        when(reader.getHelpRecords(10L, HelpRecordFilter.valueOf(type), 20L, page))
                .thenReturn(new PageImpl<>(List.of(), page, 0));
        mvc.perform(get("/api/help-records").header("Authorization", "Bearer valid-token")
                        .param("type", type).param("personId", "20").param("page", "2").param("size", "3"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(2));
        verify(reader).getHelpRecords(10L, HelpRecordFilter.valueOf(type), 20L, page);
    }

    @Test void serializesStartsAtRoleAndActualAssigneeWithoutSensitiveOrInventedFields() throws Exception {
        authenticate();
        var record = new HelpRecord(9L, 1L, HelpType.HOSPITAL, LocalDateTime.of(2026, 11, 16, 12, 30),
                20L, 10L, RequestDisplayStatus.COMPLETED, HelpRecordFilter.GIVEN);
        when(reader.getHelpRecords(10L, HelpRecordFilter.ALL, null, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(record), PageRequest.of(0, 20), 1));
        mvc.perform(get("/api/help-records").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].startsAt").value("2026-11-16T12:30:00"))
                .andExpect(jsonPath("$.data.items[0].role").value("GIVEN"))
                .andExpect(jsonPath("$.data.items[0].assigneeId").value(10))
                .andExpect(jsonPath("$.data.items[0].requesterId").value(20))
                .andExpect(jsonPath("$.data.items[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.items[0].scheduledAt").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].displayName").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].place").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].safetyInformation").doesNotExist());
    }

    @Test void serializesUrgentRequestNullsWithoutFakeTimeOrAssignee() throws Exception {
        authenticate();
        var record = new HelpRecord(9L, null, HelpType.OTHER, null, 10L, null,
                RequestDisplayStatus.EXPIRED, HelpRecordFilter.RECEIVED);
        when(reader.getHelpRecords(10L, HelpRecordFilter.ALL, null, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(record), PageRequest.of(0, 20), 1));
        mvc.perform(get("/api/help-records").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].requestId").value(9))
                .andExpect(jsonPath("$.data.items[0].startsAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.items[0].situationId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.items[0].assigneeId").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test void anonymousCannotQueryC() throws Exception {
        mvc.perform(get("/api/help-records")).andExpect(status().isUnauthorized());
        verifyNoInteractions(reader);
    }

    @Test void invalidTokenCannotQueryC() throws Exception {
        when(tokens.getUserId("invalid")).thenThrow(new IllegalArgumentException("invalid"));
        mvc.perform(get("/api/help-records").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(reader);
    }

    @Test void withdrawnViewerCannotQueryC() throws Exception {
        when(tokens.getUserId("withdrawn")).thenReturn(10L);
        when(users.existsByIdAndAccountStatus(10L, AccountStatus.ACTIVE)).thenReturn(false);
        mvc.perform(get("/api/help-records").header("Authorization", "Bearer withdrawn"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(reader);
    }

    @ParameterizedTest @ValueSource(strings = {"unknown", "given", "", " ALL"})
    void invalidFilterIs400BeforeC(String type) throws Exception {
        authenticate();
        mvc.perform(get("/api/help-records").header("Authorization", "Bearer valid-token").param("type", type))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(reader);
    }

    @ParameterizedTest @CsvSource({"page,-1", "page,no", "size,0", "size,-2", "size,2147483648",
            "personId,0", "personId,-1", "personId,no"})
    void invalidNumericInputIs400BeforeC(String parameter, String value) throws Exception {
        authenticate();
        mvc.perform(get("/api/help-records").header("Authorization", "Bearer valid-token").param(parameter, value))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(reader);
    }

    @Test void cFailureReturns500NotEmpty200() throws Exception {
        authenticate();
        when(reader.getHelpRecords(anyLong(), any(), any(), any())).thenThrow(new IllegalStateException("C failed"));
        mvc.perform(get("/api/help-records").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));
    }

    @Test void nullCResultReturns500NotEmpty200() throws Exception {
        authenticate();
        mvc.perform(get("/api/help-records").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));
    }
}
