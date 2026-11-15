package com.gyeot.domain.carerequest.dto.response;

import java.time.LocalDateTime;

/**
 * 요청 수정 결과 (data) - PATCH /api/care-requests/{requestId}
 * - result: 처리 결과 (APPLIED / PROPOSED / PROPOSAL_WITHDRAWN)
 * - proposalId, proposalResponseDeadline: PROPOSED일 때만 값이 있고, 그 외는 null
 */
public record UpdateCareRequestResponse(
        Long requestId,
        Result result,
        Long proposalId,
        LocalDateTime proposalResponseDeadline
) {

    /** 수정 처리 결과 */
    public enum Result {
        APPLIED,            // 수락 전이라 바로 반영됨
        PROPOSED,           // 수락 후라 수정안이 만들어지고 담당자 재확인 대기
        PROPOSAL_WITHDRAWN  // 대기 중 수정안을 고쳤더니 원래 요청과 모두 같아져 수정안이 철회됨
    }

    public static UpdateCareRequestResponse applied(Long requestId) {
        return new UpdateCareRequestResponse(requestId, Result.APPLIED, null, null);
    }

    public static UpdateCareRequestResponse proposed(Long requestId, Long proposalId, LocalDateTime responseDeadline) {
        return new UpdateCareRequestResponse(requestId, Result.PROPOSED, proposalId, responseDeadline);
    }

    public static UpdateCareRequestResponse proposalWithdrawn(Long requestId) {
        return new UpdateCareRequestResponse(requestId, Result.PROPOSAL_WITHDRAWN, null, null);
    }
}
