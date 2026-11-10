package com.gyeot.domain.safetycard.controller;

import com.gyeot.domain.safetycard.dto.request.SafetyInformationCreateRequest;
import com.gyeot.domain.safetycard.dto.response.CategoryItemsResponse;
import com.gyeot.domain.safetycard.dto.response.SafetyCardSummaryResponse;
import com.gyeot.domain.safetycard.dto.response.SafetyInformationResponse;
import com.gyeot.domain.safetycard.entity.InformationCategory;
import com.gyeot.domain.safetycard.service.SafetyInformationService;
import com.gyeot.global.common.ApiResponse;
import com.gyeot.global.security.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 정보 주인의 안심카드 항목 조회·작성 API입니다. (F-02, 노션 D 명세)
 * 모든 API는 로그인이 필요하고(토큰이 없으면 Security 설정에서 401), 로그인한 사용자 본인의 항목만 다룹니다.
 */
@RestController                            // JSON을 돌려주는 Controller
@RequiredArgsConstructor                   // 아래 final 필드(Service)를 채우는 생성자 자동 생성
@RequestMapping("/api/safety-information") // 이 Controller의 공통 경로
public class SafetyInformationController {

    private final SafetyInformationService safetyInformationService; // 실제 조회·작성 로직

    /** 안심카드 메인 조회: GET /api/safety-information/summary (화면 C-00) */
    @GetMapping("/summary")
    public ApiResponse<SafetyCardSummaryResponse> getSummary(
            @AuthenticationPrincipal AuthenticatedUser user) {   // 로그인한 사용자 (JWT 토큰에서 꺼냄, 요청 값으로 바꿀 수 없음)
        return ApiResponse.success(safetyInformationService.getSummary(user.userId())); // 200 + 공통 응답 형식으로 감쌈
    }

    /**
     * 분류별 항목 조회: GET /api/safety-information?category=HEALTH
     * category가 없거나 잘못된 값이면 공통 에러 400 INVALID_INPUT
     */
    @GetMapping
    public ApiResponse<CategoryItemsResponse> getCategoryItems(
            @AuthenticationPrincipal AuthenticatedUser user,     // 로그인한 사용자 (JWT 토큰에서 꺼냄, 요청 값으로 바꿀 수 없음)
            @RequestParam InformationCategory category) {   // 분류 (BASIC / HEALTH / LIFE / ADVANCE_MESSAGE)
        return ApiResponse.success(safetyInformationService.getCategoryItems(user.userId(), category)); // 200 + 공통 응답 형식으로 감쌈
    }

    /**
     * 항목 작성: POST /api/safety-information
     * 입력 규칙을 어기면 400 INVALID_INPUT, 1개만 등록하는 항목을 또 작성하면 409 SAFETY_INFO_ALREADY_EXISTS
     */
    @PostMapping
    public ResponseEntity<ApiResponse<SafetyInformationResponse>> create(
            @AuthenticationPrincipal AuthenticatedUser user,     // 로그인한 사용자 (JWT 토큰에서 꺼냄, 요청 값으로 바꿀 수 없음)
            @Valid @RequestBody SafetyInformationCreateRequest request) { // @Valid: 요청 DTO의 공통 규칙 검사
        SafetyInformationResponse response = safetyInformationService.create(user.userId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)                     // 201 Created
                .body(ApiResponse.success(response, "항목을 추가했습니다.")); // 명세의 성공 메시지
    }
}
