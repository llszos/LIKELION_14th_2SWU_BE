package com.gyeot.domain.safetycard.controller;

import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.safetycard.dto.response.SharingOptionsResponse;
import com.gyeot.domain.safetycard.dto.response.SharingOptionsResponse.CategoryOption;
import com.gyeot.domain.safetycard.entity.InformationCategory;
import com.gyeot.domain.safetycard.service.SharingOptionService;
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

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 MVC 요청 변환(로그인 사용자·파라미터·예외 응답)을 확인합니다. DB나 Docker는 필요 없습니다.
 * Security 필터 없이 Controller만 띄우므로, 로그인 상태는 JwtAuthenticationFilter가 하는 것처럼 SecurityContext에 직접 넣습니다.
 */
class SharingOptionControllerTest {

    private SharingOptionService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(SharingOptionService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SharingOptionController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()) // @AuthenticationPrincipal 값을 채워 주는 처리기
                .build();

        // 사용자 1번으로 로그인한 상태 (JwtAuthenticationFilter가 토큰 검증 후 넣는 값과 같은 형태)
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthenticatedUser(1L), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext(); // 다른 테스트에 로그인 상태가 남지 않게 정리
    }

    @Test
    void 로그인한_사용자_ID로_선택지를_조회한다() throws Exception {
        // 로그인 사용자(1번)의 ID가 Service에 그대로 넘어올 때만 응답을 돌려줌
        when(service.getSharingOptions(1L, HelpType.HOSPITAL, 5L)).thenReturn(new SharingOptionsResponse(
                List.of(new CategoryOption(InformationCategory.BASIC, "기본정보", List.of()))));

        mvc.perform(get("/api/safety-information/sharing-options")
                        .param("helpType", "HOSPITAL")
                        .param("requestId", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.categories[0].category").value("BASIC"))
                .andExpect(jsonPath("$.data.categories[0].categoryName").value("기본정보"));
    }

    @Test
    void helpType이_없으면_400이다() throws Exception {
        mvc.perform(get("/api/safety-information/sharing-options"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void helpType이_잘못된_값이면_400이다() throws Exception {
        mvc.perform(get("/api/safety-information/sharing-options")
                        .param("helpType", "SWIMMING"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }
}
