package com.gyeot.domain.carerequest.controller;

import com.gyeot.domain.carerequest.dto.request.RespondRequest;
import com.gyeot.domain.carerequest.dto.response.RespondResponse;
import com.gyeot.domain.carerequest.dto.response.WithdrawResponse;
import com.gyeot.domain.carerequest.service.CareRequestResponseService;
import com.gyeot.global.common.ApiResponse;
import com.gyeot.global.security.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 요청 응답(F-06), 수락 철회(F-08) API입니다. (#70)
 *
 * Controller는 요청 값을 꺼내 Service에 넘기고, 결과를 공통 응답(ApiResponse)으로 감쌉니다.
 * 업무 규칙과 에러 판단은 모두 Service에 있습니다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/care-requests")
public class CareRequestResponseController {
    private final CareRequestResponseService responseService;

    /**
     * POST /api/care-requests/{requestId}/respond
     * 본문: {"response": "AVAILABLE" | "UNAVAILABLE" | "CHECK_LATER"}
     * message는 수락하면 "요청을 수락했어요.", 그 외에는 null (API 명세 참조)
     */
    @PostMapping("/{requestId}/respond")
    public ApiResponse<RespondResponse> respond(
            @AuthenticationPrincipal AuthenticatedUser user, // 로그인한 사용자 (JWT에서 꺼냄)
            @PathVariable Long requestId,                    // URL의 {requestId}
            @Valid @RequestBody RespondRequest request) {    // @Valid: @NotNull 등 검증, 실패하면 400
        RespondResponse result = responseService.respond(requestId, user.userId(), request.response());
        return ApiResponse.success(result, result.accepted() ? "요청을 수락했어요." : null);
    }

    /** POST /api/care-requests/{requestId}/withdraw (본문 없음) */
    @PostMapping("/{requestId}/withdraw")
    public ApiResponse<WithdrawResponse> withdraw(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long requestId) {
        return ApiResponse.success(responseService.withdraw(requestId, user.userId()), "수락을 철회했어요.");
    }
}
