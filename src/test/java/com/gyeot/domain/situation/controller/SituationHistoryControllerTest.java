package com.gyeot.domain.situation.controller;

import com.gyeot.domain.situation.dto.response.SituationHistoryListResponse;
import com.gyeot.domain.situation.service.SituationHistoryService;
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
        when(service.list(10L, 0, 20)).thenReturn(new SituationHistoryListResponse(List.of(), 0, 20, false));
        mvc.perform(get("/api/situations/history")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        verify(service).list(10L, 0, 20);
    }

    @Test
    void 명시한_페이지와_크기를_전달한다() throws Exception {
        when(service.list(10L, 1, 100)).thenReturn(new SituationHistoryListResponse(List.of(), 1, 100, false));
        mvc.perform(get("/api/situations/history").param("page", "1").param("size", "100"))
                .andExpect(status().isOk());
        verify(service).list(10L, 1, 100);
    }

    @Test
    void 크기_제한_위반은_400이다() throws Exception {
        when(service.list(10L, 0, 101)).thenThrow(new BusinessException(CommonErrorCode.INVALID_INPUT));
        mvc.perform(get("/api/situations/history").param("size", "101"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void 미완료_상세_HTTP_성공_경로는_노출하지_않는다() throws Exception {
        mvc.perform(get("/api/situations/1/history")).andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }
}
