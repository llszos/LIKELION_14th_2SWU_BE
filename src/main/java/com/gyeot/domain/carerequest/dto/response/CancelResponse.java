package com.gyeot.domain.carerequest.dto.response;

import com.gyeot.domain.carerequest.enums.CancelReason;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;

/**
 * 요청 취소 결과 (data)
 * - status: CANCELED
 * - cancelReason: 요청자 직접 취소면 REQUESTER_CANCELED
 */
public record CancelResponse(
        Long requestId,
        CareRequestStatus status,
        CancelReason cancelReason
) {
}