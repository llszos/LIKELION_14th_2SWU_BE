package com.gyeot.domain.carerequest.controller;

import com.gyeot.domain.carerequest.dto.request.UpdateCareRequestRequest;
import com.gyeot.domain.carerequest.dto.response.ProposalAcceptResponse;
import com.gyeot.domain.carerequest.dto.response.ProposalRejectResponse;
import com.gyeot.domain.carerequest.dto.response.ProposalWithdrawResponse;
import com.gyeot.domain.carerequest.dto.response.UpdateCareRequestResponse;
import com.gyeot.domain.carerequest.service.CareRequestEditService;
import com.gyeot.domain.carerequest.service.ChangeProposalService;
import com.gyeot.global.common.ApiResponse;
import com.gyeot.global.security.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 요청 수정, 수정안 철회, 재확인 수락·거절 API입니다. (#73, F-29)
 * 업무 규칙과 에러 판단은 Service에 있고, Controller는 값을 넘기고 공통 응답으로 감쌉니다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/care-requests")
public class CareRequestEditController {
    private final CareRequestEditService editService;
    private final ChangeProposalService proposalService;

    /**
     * PATCH /api/care-requests/{requestId} - 요청자가 일정·장소·공개 항목 수정
     * 수락 전이면 바로 반영(APPLIED), 수락 후면 담당자 재확인 요청(PROPOSED)
     */
    @PatchMapping("/{requestId}")
    public ApiResponse<UpdateCareRequestResponse> update(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long requestId,
            @Valid @RequestBody UpdateCareRequestRequest request) { // 바꿀 필드가 없거나 장소가 공백이면 400
        UpdateCareRequestResponse result = editService.update(requestId, user.userId(), request);
        return ApiResponse.success(result, messageOf(result.result()));
    }

    /** POST /api/care-requests/{requestId}/change-proposals/{proposalId}/withdraw - 요청자가 대기 중 수정안 철회 */
    @PostMapping("/{requestId}/change-proposals/{proposalId}/withdraw")
    public ApiResponse<ProposalWithdrawResponse> withdraw(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long requestId,
            @PathVariable Long proposalId) {
        return ApiResponse.success(proposalService.withdraw(requestId, proposalId, user.userId()),
                "수정 요청을 철회했어요.");
    }

    /** POST /api/care-requests/{requestId}/change-proposals/{proposalId}/accept - 담당자가 수정 내용 확인 → 반영 */
    @PostMapping("/{requestId}/change-proposals/{proposalId}/accept")
    public ApiResponse<ProposalAcceptResponse> accept(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long requestId,
            @PathVariable Long proposalId) {
        return ApiResponse.success(proposalService.accept(requestId, proposalId, user.userId()),
                "변경 내용을 확인했어요.");
    }

    /** POST /api/care-requests/{requestId}/change-proposals/{proposalId}/reject - 담당자가 거절 → 요청 취소 */
    @PostMapping("/{requestId}/change-proposals/{proposalId}/reject")
    public ApiResponse<ProposalRejectResponse> reject(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long requestId,
            @PathVariable Long proposalId) {
        return ApiResponse.success(proposalService.reject(requestId, proposalId, user.userId()),
                "변경 내용을 거절해 요청이 취소됐어요.");
    }

    /** 수정 결과별 안내 문구 */
    private static String messageOf(UpdateCareRequestResponse.Result result) {
        return switch (result) {
            case APPLIED -> "요청을 수정했어요.";
            case PROPOSED -> "담당자에게 재확인을 요청했어요.";
            case PROPOSAL_WITHDRAWN -> "수정 요청을 철회했어요.";
        };
    }
}