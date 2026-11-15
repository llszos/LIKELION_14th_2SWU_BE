package com.gyeot.domain.situation.controller;

import com.gyeot.domain.situation.service.SituationCreateService;
import com.gyeot.domain.situation.dto.response.SituationCreateResponse;
import com.gyeot.domain.situation.entity.SituationStatus;
import com.gyeot.domain.situation.exception.SituationCreationErrorCode;
import com.gyeot.domain.user.entity.AccountStatus;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.exception.*;
import com.gyeot.global.security.*;
import com.gyeot.global.security.csrf.RefreshCookieCsrfMatcher;
import com.gyeot.global.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Mock Service로 HTTP 연결만 검증합니다. 실제 종류 검증을 통과하는 생성 성공 테스트가 아닙니다. */
@WebMvcTest(SituationCreateController.class)
@Import({SecurityConfig.class,PublicApiRequestMatcher.class,RefreshCookieCsrfMatcher.class,
        ApiAuthenticationEntryPoint.class,ApiAccessDeniedHandler.class,GlobalExceptionHandler.class})
class SituationCreateControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SituationCreateService service;
    @MockitoBean JwtTokenProvider tokens;
    @MockitoBean UserRepository users;
    @MockitoBean com.gyeot.global.security.cookie.RefreshTokenCookieService refreshCookies;
    private final String body="""
            {"situationType":"test-type","name":"이름","participantIds":[],"startAt":"2026-11-20T09:00:00","endAt":"2026-11-20T10:00:00","creatorId":999}
            """;
    private void auth(){when(tokens.getUserId("valid")).thenReturn(10L);when(users.existsByIdAndAccountStatus(10L,AccountStatus.ACTIVE)).thenReturn(true);}
    @Test void mockedSuccessUsesPrincipalAnd201Envelope()throws Exception{auth();when(service.create(eq(10L),any())).thenReturn(new SituationCreateResponse(7L,10L,SituationStatus.ACTIVE));mvc.perform(post("/api/situations").header("Authorization","Bearer valid").contentType("application/json").content(body)).andExpect(status().isCreated()).andExpect(jsonPath("$.data.situationId").value(7)).andExpect(jsonPath("$.data.creatorId").value(10)).andExpect(jsonPath("$.data.status").value("ACTIVE"));verify(service).create(eq(10L),any());}
    @Test void unavailableTypeUsesExplicitErrorInsteadOfSuccess()throws Exception{auth();when(service.create(eq(10L),any())).thenThrow(new BusinessException(SituationCreationErrorCode.SITUATION_TYPE_NOT_CONFIGURED));mvc.perform(post("/api/situations").header("Authorization","Bearer valid").contentType("application/json").content(body)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SITUATION_TYPE_NOT_CONFIGURED"));}
    @Test void anonymousIs401()throws Exception{mvc.perform(post("/api/situations").contentType("application/json").content(body)).andExpect(status().isUnauthorized());verifyNoInteractions(service);}
    @Test void invalidInputIs400BeforeService()throws Exception{auth();mvc.perform(post("/api/situations").header("Authorization","Bearer valid").contentType("application/json").content("{}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));verifyNoInteractions(service);}

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void invalidDtoAndJsonNeverReachService(String invalidBody) throws Exception {
        auth();
        mvc.perform(post("/api/situations").header("Authorization", "Bearer valid")
                        .contentType("application/json").content(invalidBody))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(service);
    }

    static java.util.stream.Stream<String> invalidBodies() {
        String valid = """
                {"situationType":"unconfirmed","name":"이름","participantIds":[],
                 "startAt":"2026-11-20T09:00:00","endAt":"2026-11-20T10:00:00"}
                """;
        return java.util.stream.Stream.of(
                valid.replace("unconfirmed", " "),
                valid.replace("unconfirmed", "x".repeat(31)),
                valid.replace("이름", " "),
                valid.replace("이름", "가".repeat(151)),
                valid.replace("[]", "null"),
                valid.replace("[]", "[20,null]"),
                valid.replace("2026-11-20T10:00:00", "2026-11-20T09:00:00"),
                valid.replace("2026-11-20T10:00:00", "2026-11-20T08:00:00"),
                valid.replace("\"2026-11-20T09:00:00\"", "null"),
                valid.replace("2026-11-20T09:00:00", "not-a-date"),
                valid.replace("\"name\":", "\"customSituationType\":\"" + "가".repeat(16) + "\",\"name\":"),
                "{broken-json");
    }

    @Test
    void mocked201PreservesBoundaryFieldsWithoutDeclaringATypeSupported() throws Exception {
        auth();
        when(service.create(eq(10L), any())).thenReturn(new SituationCreateResponse(7L, 10L, SituationStatus.ACTIVE));
        String boundaryBody = body.replace("이름", "가".repeat(150))
                .replace("\"name\":", "\"customSituationType\":\"" + "가".repeat(15) + "\",\"name\":");
        mvc.perform(post("/api/situations").header("Authorization", "Bearer valid")
                        .contentType("application/json").content(boundaryBody))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.creatorId").value(10));
        var request = org.mockito.ArgumentCaptor.forClass(com.gyeot.domain.situation.dto.request.SituationCreateRequest.class);
        verify(service).create(eq(10L), request.capture());
        org.assertj.core.api.Assertions.assertThat(request.getValue().name()).hasSize(150);
        org.assertj.core.api.Assertions.assertThat(request.getValue().customSituationType()).hasSize(15);
        org.assertj.core.api.Assertions.assertThat(request.getValue().participantIds()).isEmpty();
    }
}
