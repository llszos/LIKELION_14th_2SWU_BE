package com.gyeot.domain.situation.controller;

import com.gyeot.domain.situation.repository.SituationParticipantRepository;
import com.gyeot.domain.situation.repository.SituationRepository;
import com.gyeot.domain.situation.service.*;
import com.gyeot.domain.user.entity.AccountStatus;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.exception.GlobalExceptionHandler;
import com.gyeot.global.security.*;
import com.gyeot.global.security.csrf.RefreshCookieCsrfMatcher;
import com.gyeot.global.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 Service·Resolver가 잘못된 입력을 저장 전에 거부하는지 확인합니다. DB는 사용하지 않습니다. */
@WebMvcTest(SituationCreateController.class)
@Import({SituationCreateService.class, SituationCreationTypeResolver.class,
        SecurityConfig.class, PublicApiRequestMatcher.class, RefreshCookieCsrfMatcher.class,
        ApiAuthenticationEntryPoint.class, ApiAccessDeniedHandler.class, GlobalExceptionHandler.class})
class SituationCreateReadinessTest {
    @Autowired MockMvc mvc;
    @MockitoBean SituationCreateParticipantValidator participantsValidation;
    @MockitoBean SituationCreateWriter writer;
    @MockitoBean SituationRepository situations;
    @MockitoBean SituationParticipantRepository participants;
    @MockitoBean Clock clock;
    @MockitoBean JwtTokenProvider tokens;
    @MockitoBean UserRepository users;
    @MockitoBean com.gyeot.global.security.cookie.RefreshTokenCookieService refreshCookies;

    @BeforeEach void authenticate() {
        when(tokens.getUserId("valid")).thenReturn(10L);
        when(users.existsByIdAndAccountStatus(10L, AccountStatus.ACTIVE)).thenReturn(true);
    }

    @Test void unsupportedTypeReturns400WithoutAnyPartialStorageOrParticipantLookup() throws Exception {
        for (String custom : new String[]{"null", "\"기타 문구\""}) {
            mvc.perform(post("/api/situations").header("Authorization", "Bearer valid")
                            .contentType("application/json").content(body("[]", custom)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value("error"))
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        verifyNoInteractions(participantsValidation, writer, situations, participants, clock);
    }

    @Test void nullParticipantIdIs400BeforeStorage() throws Exception {
        mvc.perform(post("/api/situations").header("Authorization", "Bearer valid")
                        .contentType("application/json").content(body("[null]", "null")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(participantsValidation, writer, situations, participants, clock);
    }

    private String body(String ids, String custom) {
        return """
                {"situationType":"unconfirmed","customSituationType":%s,"name":"이름",
                 "participantIds":%s,"startAt":"2026-11-20T09:00:00","endAt":"2026-11-20T10:00:00"}
                """.formatted(custom, ids);
    }
}
