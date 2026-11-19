package com.gyeot.domain.carerequest.controller;

import com.gyeot.domain.carerequest.dto.response.CancelResponse;
import com.gyeot.domain.carerequest.dto.response.RequestStatusResponse;
import com.gyeot.domain.carerequest.service.CareRequestStatusService;
import com.gyeot.global.common.ApiResponse;
import com.gyeot.global.security.auth.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 돌봄 시작, 완료, 요청 취소 API입니다. (#72, F-08, F-29)
 * 세 API 모두 요청 본문이 없고, 결과를 공통 응답(ApiResponse)으로 감싸 돌려줍니다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/care-requests")
public class CareRequestStatusController {
    private final CareRequestStatusService statusService;

    /** POST /api/care-requests/{requestId}/start - 담당자가 시작 */
    @PostMapping("/{requestId}/start")
    public ApiResponse<RequestStatusResponse> start(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long requestId) {
        return ApiResponse.success(statusService.start(requestId, user.userId()), "돌봄을 시작했어요.");
    }

    /** POST /api/care-requests/{requestId}/complete - 담당자, 요청자가 완료 */
    @PostMapping("/{requestId}/complete")
    public ApiResponse<RequestStatusResponse> complete(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long requestId) {
        return ApiResponse.success(statusService.complete(requestId, user.userId()), "요청을 완료했어요.");
    }

    /** POST /api/care-requests/{requestId}/cancel - 요청자가 취소 */
    @PostMapping("/{requestId}/cancel")
    public ApiResponse<CancelResponse> cancel(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long requestId) {
        return ApiResponse.success(statusService.cancel(requestId, user.userId()), "요청을 취소했어요.");
    }
}
