package com.gyeot.domain.carerequest.controller;

import com.gyeot.domain.carerequest.dto.request.CreateCareRequestRequest;
import com.gyeot.domain.carerequest.dto.response.CareRequestDetailResponse;
import com.gyeot.domain.carerequest.dto.response.CreateCareRequestResponse;
import com.gyeot.domain.carerequest.service.CareRequestCreateService;
import com.gyeot.domain.carerequest.service.CareRequestDetailService;
import com.gyeot.global.common.ApiResponse;
import com.gyeot.global.security.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 도움 요청 생성, 상세 조회 API입니다. (#69, F-05, F-08)
 * 업무 규칙과 에러 판단은 Service에 있고, Controller는 값을 넘기고 공통 응답으로 감쌉니다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/care-requests")
public class CareRequestController {
    private final CareRequestCreateService createService;
    private final CareRequestDetailService detailService;

    /** POST /api/care-requests - 상황 안에서 한 명에게 요청 보내기 (201) */
    @PostMapping
    public ResponseEntity<ApiResponse<CreateCareRequestResponse>> create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateCareRequestRequest request) { // 필수값 누락, 장소 공백이면 400 INVALID_INPUT
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(createService.create(user.userId(), request), "요청을 보냈어요."));
    }

    /** GET /api/care-requests/{requestId} - 보는 사람에 맞춘 요청 상세 */
    @GetMapping("/{requestId}")
    public ApiResponse<CareRequestDetailResponse> getDetail(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long requestId) {
        return ApiResponse.success(detailService.getDetail(requestId, user.userId()));
    }
}
