package com.gyeot.domain.access.controller;

import com.gyeot.domain.access.dto.response.SharedInformationResponse;
import com.gyeot.domain.access.dto.response.SharedInformationResponse.SharedCategory;
import com.gyeot.domain.access.dto.response.SharedInformationResponse.SharedItem;
import com.gyeot.domain.access.dto.response.UnavailableReason;
import com.gyeot.domain.access.service.SharedInformationService;
import com.gyeot.domain.safetycard.entity.InformationCategory;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.exception.SafetyCardErrorCode;
import com.gyeot.global.exception.BusinessException;
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
 * 전달받은 정보 조회의 실제 MVC 요청 변환(로그인 사용자, 경로 값, 응답 형태, 예외 응답)을 확인합니다. DB나 Docker는 필요 없습니다.
 * Security 필터 없이 Controller만 띄우므로, 로그인 상태는 JwtAuthenticationFilter가 하는 것처럼 SecurityContext에 직접 넣습니다.
 */
class SharedInformationControllerTest {

    private static final Long USER_ID = 7L; // 로그인한 담당자

    private SharedInformationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(SharedInformationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SharedInformationController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()) // @AuthenticationPrincipal 값을 채워 주는 처리기
                .build();

        // 사용자 7번으로 로그인한 상태 (JwtAuthenticationFilter가 토큰 검증 후 넣는 값과 같은 형태)
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthenticatedUser(USER_ID), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext(); // 다른 테스트에 로그인 상태가 남지 않게 정리
    }

    @Test
    void 볼_수_있으면_분류별_항목과_표시_값을_명세_형태로_내려준다() throws Exception {
        // 경로의 요청 ID와 로그인 사용자 ID가 Service에 그대로 넘어올 때만 응답을 돌려줌
        when(service.getSharedInformation(31L, USER_ID)).thenReturn(SharedInformationResponse.accessible(
                31L, LocalDateTime.of(2026, 10, 2, 17, 0),
                List.of(new SharedCategory(InformationCategory.HEALTH, InformationCategory.HEALTH.getDescription(), List.of(
                        new SharedItem(12L, InformationType.MEDICATION, InformationType.MEDICATION.getDescription(), "혈압약",
                                "아침 1정", LocalDateTime.of(2026, 10, 1, 9, 30), true, false, true))))));

        mvc.perform(get("/api/care-requests/31/shared-information"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.requestId").value(31))
                .andExpect(jsonPath("$.data.accessible").value(true))
                .andExpect(jsonPath("$.data.accessUntil").value("2026-10-02T17:00:00")) // 시간대 표기 없는 KST (API 가이드 7번)
                .andExpect(jsonPath("$.data.unavailableReason").isEmpty())
                .andExpect(jsonPath("$.data.categories[0].category").value("HEALTH"))
                .andExpect(jsonPath("$.data.categories[0].categoryName").value(InformationCategory.HEALTH.getDescription()))
                .andExpect(jsonPath("$.data.categories[0].items[0].informationId").value(12))
                .andExpect(jsonPath("$.data.categories[0].items[0].informationType").value("MEDICATION"))
                .andExpect(jsonPath("$.data.categories[0].items[0].updatedAt").value("2026-10-01T09:30:00"))
                .andExpect(jsonPath("$.data.categories[0].items[0].updated").value(true))
                .andExpect(jsonPath("$.data.categories[0].items[0].added").value(false))
                .andExpect(jsonPath("$.data.categories[0].items[0].sensitive").value(true));
    }

    @Test
    void 볼_수_없으면_200과_이유를_내려주고_항목은_빈_배열이다() throws Exception {
        when(service.getSharedInformation(31L, USER_ID)).thenReturn(SharedInformationResponse.unavailable(
                31L, LocalDateTime.of(2026, 10, 2, 17, 0), UnavailableReason.REQUEST_COMPLETED));

        mvc.perform(get("/api/care-requests/31/shared-information"))
                .andExpect(status().isOk()) // 접근 종료 안내는 오류가 아니라 정상 화면 (API 가이드 2. 판단 기준)
                .andExpect(jsonPath("$.data.accessible").value(false))
                .andExpect(jsonPath("$.data.accessUntil").value("2026-10-02T17:00:00"))
                .andExpect(jsonPath("$.data.unavailableReason").value("REQUEST_COMPLETED"))
                .andExpect(jsonPath("$.data.categories").isEmpty());
    }

    @Test
    void 이_요청과_무관한_사람이면_404_SHARED_INFO_NOT_FOUND이다() throws Exception {
        when(service.getSharedInformation(31L, USER_ID))
                .thenThrow(new BusinessException(SafetyCardErrorCode.SHARED_INFO_NOT_FOUND));

        mvc.perform(get("/api/care-requests/31/shared-information"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.code").value("SHARED_INFO_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("전달받은 정보를 찾을 수 없습니다."))
                .andExpect(jsonPath("$.data").isEmpty());
    }
}
