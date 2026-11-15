package com.gyeot.domain.situation.controller;

import com.gyeot.domain.situation.dto.internal.SituationParticipantRemovalResult;
import com.gyeot.domain.situation.entity.SituationParticipantStatus;
import com.gyeot.domain.situation.exception.*;
import com.gyeot.domain.situation.service.SituationParticipantRemovalService;
import com.gyeot.domain.user.entity.AccountStatus;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.exception.*;
import com.gyeot.global.security.*;
import com.gyeot.global.security.csrf.RefreshCookieCsrfMatcher;
import com.gyeot.global.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.json.JsonCompareMode;

import java.time.LocalDateTime;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SituationParticipantRemovalController.class)
@Import({SecurityConfig.class,PublicApiRequestMatcher.class,RefreshCookieCsrfMatcher.class,
        ApiAuthenticationEntryPoint.class,ApiAccessDeniedHandler.class,GlobalExceptionHandler.class})
class SituationParticipantRemovalControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SituationParticipantRemovalService service;
    @MockitoBean JwtTokenProvider tokens;
    @MockitoBean UserRepository users;
    @MockitoBean com.gyeot.global.security.cookie.RefreshTokenCookieService refreshCookies;

    private void authenticate() {
        when(tokens.getUserId("valid")).thenReturn(10L);
        when(users.existsByIdAndAccountStatus(10L,AccountStatus.ACTIVE)).thenReturn(true);
    }
    @Test void usesPrincipalAndSerializesActualStatusIncludingRetry() throws Exception {
        authenticate();
        for (var state : new SituationParticipantStatus[]{SituationParticipantStatus.REMOVED,SituationParticipantStatus.LEFT}) {
            when(service.remove(3L,7L,10L)).thenReturn(new SituationParticipantRemovalResult(7L,state,
                    LocalDateTime.of(2026,11,15,10,0)));
            mvc.perform(delete("/api/situations/3/participants/7").header("Authorization","Bearer valid").param("userId","999"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("success"))
                    .andExpect(jsonPath("$.data.participantId").value(7))
                    .andExpect(jsonPath("$.data.status").value(state.name()))
                    .andExpect(jsonPath("$.data.leftAt").value("2026-11-15T10:00:00"))
                    .andExpect(content().json("""
                            {"status":"success","code":null,"message":null,
                             "data":{"participantId":7,"status":"%s","leftAt":"2026-11-15T10:00:00"}}
                            """.formatted(state.name()), JsonCompareMode.STRICT));
        }
        verify(service,times(2)).remove(3L,7L,10L);
        verifyNoMoreInteractions(service);
    }
    @Test void anonymousIsUnauthorized() throws Exception {
        mvc.perform(delete("/api/situations/3/participants/7")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void invalidTokenIsUnauthorized() throws Exception {
        when(tokens.getUserId("bad")).thenThrow(new IllegalArgumentException("bad"));
        mvc.perform(delete("/api/situations/3/participants/7").header("Authorization","Bearer bad"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void mapsBusinessErrorsToCommonEnvelope() throws Exception {
        authenticate();
        for (ErrorCode code : new ErrorCode[]{SituationRemovalErrorCode.SITUATION_NOT_FOUND,
                SituationRemovalErrorCode.SITUATION_PARTICIPANT_NOT_FOUND,SituationRemovalErrorCode.SITUATION_FORBIDDEN,
                SituationRemovalErrorCode.SITUATION_OWNER_REMOVAL_NOT_ALLOWED,SituationLeaveErrorCode.SITUATION_ENDED}) {
            doThrow(new BusinessException(code)).when(service).remove(3L,7L,10L);
            mvc.perform(delete("/api/situations/3/participants/7").header("Authorization","Bearer valid"))
                    .andExpect(status().is(code.getHttpStatus().value()))
                    .andExpect(jsonPath("$.status").value("error"))
                    .andExpect(jsonPath("$.code").value(code.getCode()));
        }
    }
    @Test void malformedIdIsBadRequestBeforeService() throws Exception {
        authenticate();
        mvc.perform(delete("/api/situations/3/participants/invalid").header("Authorization","Bearer valid"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(service);
    }
}
