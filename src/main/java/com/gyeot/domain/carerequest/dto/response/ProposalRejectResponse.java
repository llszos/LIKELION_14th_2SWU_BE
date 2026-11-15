package com.gyeot.domain.carerequest.dto.response;

import com.gyeot.domain.carerequest.enums.CancelReason;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.ProposalStatus;

/**
 * 재확인 거절 결과 (data) - POST .../change-proposals/{proposalId}/reject
 * - proposalStatus: REJECTED
 * - status: CANCELED, cancelReason: RECONFIRMATION_REJECTED (거절하면 요청이 취소됨, F-29)
 */
public record ProposalRejectResponse(
        Long requestId,
        Long proposalId,
        ProposalStatus proposalStatus,
        CareRequestStatus status,
        CancelReason cancelReason
) {
}
