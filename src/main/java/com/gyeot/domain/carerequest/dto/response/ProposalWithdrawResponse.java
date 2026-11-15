package com.gyeot.domain.carerequest.dto.response;

import com.gyeot.domain.carerequest.enums.ProposalStatus;

/**
 * 수정안 철회 결과 (data) - POST .../change-proposals/{proposalId}/withdraw
 * - proposalStatus: INVALIDATED
 */
public record ProposalWithdrawResponse(
        Long requestId,
        Long proposalId,
        ProposalStatus proposalStatus
) {
}
