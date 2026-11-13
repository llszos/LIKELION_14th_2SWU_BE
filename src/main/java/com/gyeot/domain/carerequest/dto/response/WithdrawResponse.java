package com.gyeot.domain.carerequest.dto.response;

import com.gyeot.domain.carerequest.enums.CareRequestStatus;

/**
 * 수락 철회 결과 (data)입니다.
 * - status: 요청 상태.
 * - 철회하면 REQUESTED로 돌아감
 */
public record WithdrawResponse(
        Long requestId,
        CareRequestStatus status
) {
}
