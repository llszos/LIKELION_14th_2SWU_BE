package com.gyeot.domain.access.controller;

import com.gyeot.domain.access.dto.response.ReceivedSharingResponse;
import com.gyeot.domain.access.dto.response.ReceivedSharingResponse.ReceivedSharing;
import com.gyeot.domain.access.dto.response.SharedItemName;
import com.gyeot.domain.access.service.ReceivedSharingService;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.safetycard.entity.InformationType;
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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공유 관리 API의 실제 MVC 요청 변환(로그인 사용자, 쿼리 값, 응답 형태, 입력 오류)을 확인합니다. DB나 Docker는 필요 없습니다.
 * Security 필터 없이 Controller만 띄우므로, 로그인 상태는 JwtAuthenticationFilter가 하는 것처럼 SecurityContext에 직접 넣습니다.
 */
class SharingControllerTest {

    private static final Long USER_ID = 20L; // 로그인한 사용자 (받는 사람)

    private ReceivedSharingService receivedSharingService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        receivedSharingService = mock(ReceivedSharingService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SharingController(receivedSharingService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()) // @AuthenticationPrincipal 값을 채워 주는 처리기
                .build();

        // 사용자 20번으로 로그인한 상태 (JwtAuthenticationFilter가 토큰 검증 후 넣는 값과 같은 형태)
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthenticatedUser(USER_ID), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext(); // 다른 테스트에 로그인 상태가 남지 않게 정리
    }

    @Test
    void 받은_정보를_명세_형태로_내려준다() throws Exception {
        // 로그인 사용자(받는 사람)와 쿼리의 userId(정보 주인)가 Service에 그대로 넘어올 때만 응답을 돌려줌
        when(receivedSharingService.getReceived(USER_ID, 10L)).thenReturn(new ReceivedSharingResponse(List.of(
                new ReceivedSharing(45L, HelpType.HOSPITAL, LocalDateTime.of(2026, 10, 8, 10, 0),
                        LocalDateTime.of(2026, 10, 8, 20, 0),
                        List.of(SharedItemName.of(InformationType.ALLERGY, "페니실린"))))));

        mvc.perform(get("/api/sharings/received").param("userId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.items[0].requestId").value(45))
                .andExpect(jsonPath("$.data.items[0].helpType").value("HOSPITAL"))
                .andExpect(jsonPath("$.data.items[0].startsAt").value("2026-10-08T10:00:00"))
                .andExpect(jsonPath("$.data.items[0].accessUntil").value("2026-10-08T20:00:00"))
                .andExpect(jsonPath("$.data.items[0].sharedItems[0].informationType").value("ALLERGY"))
                .andExpect(jsonPath("$.data.items[0].sharedItems[0].typeName").value(InformationType.ALLERGY.getDescription()))
                .andExpect(jsonPath("$.data.items[0].sharedItems[0].title").value("페니실린"));
    }

    @Test
    void 받은_정보가_없으면_빈_배열이다() throws Exception {
        when(receivedSharingService.getReceived(USER_ID, 10L)).thenReturn(new ReceivedSharingResponse(List.of()));

        mvc.perform(get("/api/sharings/received").param("userId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void userId가_없으면_400이다() throws Exception {
        mvc.perform(get("/api/sharings/received"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void userId가_숫자가_아니면_400이다() throws Exception {
        mvc.perform(get("/api/sharings/received").param("userId", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }
}
