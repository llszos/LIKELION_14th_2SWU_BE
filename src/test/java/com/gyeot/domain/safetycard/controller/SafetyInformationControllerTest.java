package com.gyeot.domain.safetycard.controller;

import com.gyeot.domain.safetycard.dto.request.SafetyInformationCreateRequest;
import com.gyeot.domain.safetycard.dto.response.CategoryItemsResponse;
import com.gyeot.domain.safetycard.dto.response.SafetyCardSummaryResponse;
import com.gyeot.domain.safetycard.dto.response.SafetyInformationResponse;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.entity.InformationCategory;
import com.gyeot.domain.safetycard.service.SafetyInformationService;
import com.gyeot.global.exception.GlobalExceptionHandler;
import com.gyeot.global.security.auth.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 MVC 요청 변환(로그인 사용자·파라미터·예외 응답)을 확인합니다. DB나 Docker는 필요 없습니다.
 * Security 필터 없이 Controller만 띄우므로, 로그인 상태는 JwtAuthenticationFilter가 하는 것처럼 SecurityContext에 직접 넣습니다.
 */
class SafetyInformationControllerTest {

    private SafetyInformationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(SafetyInformationService.class);
        // Spring 전체를 띄우지 않고 이 Controller와 공통 예외 처리기만 연결
        mvc = MockMvcBuilders.standaloneSetup(new SafetyInformationController(service))
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
    void 안심카드_메인을_조회한다() throws Exception {
        when(service.getSummary(1L)).thenReturn(new SafetyCardSummaryResponse(List.of(), 2L));

        mvc.perform(get("/api/safety-information/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sharingCount").value(2)); // 공유 중 건수
    }

    @Test
    void 분류별_항목을_조회한다() throws Exception {
        when(service.getCategoryItems(1L, InformationCategory.HEALTH))
                .thenReturn(new CategoryItemsResponse(InformationCategory.HEALTH, "건강", List.of()));

        mvc.perform(get("/api/safety-information").param("category", "HEALTH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.category").value("HEALTH"))
                .andExpect(jsonPath("$.data.categoryName").value("건강"));
    }

    @Test
    void category가_없거나_잘못되면_400이다() throws Exception {
        // category 없음
        mvc.perform(get("/api/safety-information"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        // 없는 분류 값
        mvc.perform(get("/api/safety-information").param("category", "FOOD"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void 항목을_작성하면_201과_성공_메시지를_준다() throws Exception {
        when(service.create(1L, new SafetyInformationCreateRequest(InformationType.MEDICATION, "혈압약", "아침 식후 1정")))
                .thenReturn(new SafetyInformationResponse(12L, InformationType.MEDICATION, "복용약", "혈압약", "아침 식후 1정",
                        true, null)); // 시각 직렬화는 이 테스트의 관심사가 아니라 null

        mvc.perform(post("/api/safety-information")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"informationType":"MEDICATION","title":"혈압약","informationValue":"아침 식후 1정"}
                                """))
                .andExpect(status().isCreated())                                   // 201
                .andExpect(jsonPath("$.message").value("항목을 추가했습니다."))     // 명세의 성공 메시지
                .andExpect(jsonPath("$.data.informationId").value(12));
    }

    @Test
    void 내용이_공백이거나_유형이_없으면_서비스까지_가지_않고_400이다() throws Exception {
        // 내용이 공백만 있음 (@NotBlank)
        mvc.perform(post("/api/safety-information")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"informationType":"MEDICATION","title":"혈압약","informationValue":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        // 항목 유형 없음 (@NotNull)
        mvc.perform(post("/api/safety-information")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"혈압약","informationValue":"아침 식후 1정"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        verify(service, never()).create(any(), any()); // 요청 검증에서 막혀 Service는 호출되지 않음
    }
}
