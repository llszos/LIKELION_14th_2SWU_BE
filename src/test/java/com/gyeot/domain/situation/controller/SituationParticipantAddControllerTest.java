package com.gyeot.domain.situation.controller;

import com.gyeot.domain.situation.dto.response.SituationParticipantAddResponse;
import com.gyeot.domain.situation.entity.*;
import com.gyeot.domain.situation.service.SituationParticipantAddService;
import com.gyeot.domain.user.entity.AccountStatus;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.exception.GlobalExceptionHandler;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SituationParticipantAddController.class)
@Import({SecurityConfig.class,PublicApiRequestMatcher.class,RefreshCookieCsrfMatcher.class,
        ApiAuthenticationEntryPoint.class,ApiAccessDeniedHandler.class,GlobalExceptionHandler.class})
class SituationParticipantAddControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SituationParticipantAddService service;
    @MockitoBean JwtTokenProvider tokens;
    @MockitoBean UserRepository users;
    @MockitoBean com.gyeot.global.security.cookie.RefreshTokenCookieService refreshCookies;
    private void auth(){when(tokens.getUserId("valid")).thenReturn(10L);when(users.existsByIdAndAccountStatus(10L,AccountStatus.ACTIVE)).thenReturn(true);}
    @Test void createsWithPrincipalAndExactResponse() throws Exception {
        auth();when(service.add(3L,10L,20L)).thenReturn(new SituationParticipantAddResponse(7L,3L,20L,SituationParticipantRole.PARTICIPANT,SituationParticipantStatus.ACTIVE,LocalDateTime.of(2026,11,15,10,0)));
        mvc.perform(post("/api/situations/3/participants").header("Authorization","Bearer valid").contentType("application/json").content("{\"userId\":20,\"callerId\":999}"))
                .andExpect(status().isCreated()).andExpect(content().json("""
                        {"status":"success","code":null,"message":null,"data":{"participantId":7,"situationId":3,"userId":20,"role":"PARTICIPANT","status":"ACTIVE","joinedAt":"2026-11-15T10:00:00"}}
                        """,JsonCompareMode.STRICT));verify(service).add(3L,10L,20L);
    }
    @Test void anonymousCannotAdd() throws Exception {mvc.perform(post("/api/situations/3/participants").contentType("application/json").content("{\"userId\":20}")).andExpect(status().isUnauthorized());verifyNoInteractions(service);}
    @Test void missingNullAndMalformedUserIdAreBadRequest() throws Exception {auth();for(String body:new String[]{"{}","{\"userId\":null}","{\"userId\":\"invalid\"}"})mvc.perform(post("/api/situations/3/participants").header("Authorization","Bearer valid").contentType("application/json").content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));verifyNoInteractions(service);}
}
