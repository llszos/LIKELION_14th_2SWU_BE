package com.gyeot.domain.situation.controller;

import com.gyeot.domain.situation.dto.response.SituationParticipantsResponse;
import com.gyeot.domain.situation.entity.*;
import com.gyeot.domain.situation.exception.SituationRemovalErrorCode;
import com.gyeot.domain.situation.service.SituationParticipantsQueryService;
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
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SituationParticipantsController.class)
@Import({SecurityConfig.class,PublicApiRequestMatcher.class,RefreshCookieCsrfMatcher.class,
        ApiAuthenticationEntryPoint.class,ApiAccessDeniedHandler.class,GlobalExceptionHandler.class})
class SituationParticipantsControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SituationParticipantsQueryService service;
    @MockitoBean JwtTokenProvider tokens;
    @MockitoBean UserRepository users;
    @MockitoBean com.gyeot.global.security.cookie.RefreshTokenCookieService refreshCookies;
    private void authenticate() {
        when(tokens.getUserId("valid")).thenReturn(20L);
        when(users.existsByIdAndAccountStatus(20L,AccountStatus.ACTIVE)).thenReturn(true);
    }
    @Test void returnsExactPublicFieldsAndUsesPrincipal() throws Exception {
        authenticate();
        when(service.list(3L,20L)).thenReturn(new SituationParticipantsResponse(List.of(
                new SituationParticipantsResponse.Item(7L,20L,SituationParticipantRole.PARTICIPANT,
                        SituationParticipantStatus.ACTIVE,LocalDateTime.of(2026,11,15,9,0),null))));
        mvc.perform(get("/api/situations/3/participants").header("Authorization","Bearer valid").param("userId","999"))
                .andExpect(status().isOk()).andExpect(content().json("""
                        {"status":"success","code":null,"message":null,"data":{"items":[{
                        "participantId":7,"userId":20,"role":"PARTICIPANT","status":"ACTIVE",
                        "joinedAt":"2026-11-15T09:00:00","leftAt":null}]}}
                        """,JsonCompareMode.STRICT));
        verify(service).list(3L,20L);
        verifyNoMoreInteractions(service);
    }
    @Test void anonymousIsUnauthorized() throws Exception {
        mvc.perform(get("/api/situations/3/participants")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void invalidAndInactiveTokensAreUnauthorized() throws Exception {
        when(tokens.getUserId("bad")).thenThrow(new IllegalArgumentException("bad"));
        when(tokens.getUserId("inactive")).thenReturn(20L);
        for(String token:List.of("bad","inactive"))
            mvc.perform(get("/api/situations/3/participants").header("Authorization","Bearer "+token))
                    .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void inaccessibleAndMissingSituationUseSameNotFoundEnvelope() throws Exception {
        authenticate();
        when(service.list(3L,20L)).thenThrow(new BusinessException(SituationRemovalErrorCode.SITUATION_NOT_FOUND));
        mvc.perform(get("/api/situations/3/participants").header("Authorization","Bearer valid"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SITUATION_NOT_FOUND"));
    }
    @Test void malformedIdIsBadRequest() throws Exception {
        authenticate();
        mvc.perform(get("/api/situations/invalid/participants").header("Authorization","Bearer valid"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(service);
    }
}
