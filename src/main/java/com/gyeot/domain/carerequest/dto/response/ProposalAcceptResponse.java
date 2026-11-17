package com.gyeot.domain.carerequest.dto.response;

import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.ProposalStatus;

/**
 * 재확인 수락 결과 (data) - POST .../change-proposals/{proposalId}/accept
 * - proposalStatus: ACCEPTED
 * - status: 요청 상태 (ACCEPTED, 수정 내용이 반영된 채로 수락 유지)
 */
public record ProposalAcceptResponse(
        Long requestId,
        Long proposalId,
        ProposalStatus proposalStatus,
        CareRequestStatus status
) {
}