package com.gyeot.domain.safetycard.controller;

import com.gyeot.domain.safetycard.dto.response.CategoryItemsResponse;
import com.gyeot.domain.safetycard.dto.response.SafetyCardSummaryResponse;
import com.gyeot.domain.safetycard.entity.InformationCategory;
import com.gyeot.domain.safetycard.service.SafetyInformationService;
import com.gyeot.global.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 정보 주인의 안심카드 항목 조회·작성 API입니다. (F-02, 노션 D 명세) */
@RestController                            // JSON을 돌려주는 Controller
@RequiredArgsConstructor                   // 아래 final 필드(Service)를 채우는 생성자 자동 생성
@RequestMapping("/api/safety-information") // 이 Controller의 공통 경로
public class SafetyInformationController {

    private final SafetyInformationService safetyInformationService; // 실제 조회·작성 로직

    /** 안심카드 메인 조회: GET /api/safety-information/summary (화면 C-00) */
    @GetMapping("/summary")
    public ApiResponse<SafetyCardSummaryResponse> getSummary(
            // TODO: #33(JWT 인증) 병합 후 @AuthenticationPrincipal AuthenticatedUser로 교체. 그전까지 임시 사용자 ID 헤더 사용
            @RequestHeader("X-User-Id") Long userId) {
        return ApiResponse.success(safetyInformationService.getSummary(userId)); // 200 + 공통 응답 형식으로 감쌈
    }

    /**
     * 분류별 항목 조회: GET /api/safety-information?category=HEALTH
     * category가 없거나 잘못된 값이면 공통 에러 400 INVALID_INPUT
     */
    @GetMapping
    public ApiResponse<CategoryItemsResponse> getCategoryItems(
            // TODO: #33(JWT 인증) 병합 후 @AuthenticationPrincipal AuthenticatedUser로 교체. 그전까지 임시 사용자 ID 헤더 사용
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam InformationCategory category) {   // 분류 (BASIC / HEALTH / LIFE / ADVANCE_MESSAGE)
        return ApiResponse.success(safetyInformationService.getCategoryItems(userId, category)); // 200 + 공통 응답 형식으로 감쌈
    }
}
