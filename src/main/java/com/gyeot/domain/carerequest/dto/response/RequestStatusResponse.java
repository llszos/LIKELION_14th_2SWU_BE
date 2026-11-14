package com.gyeot.domain.carerequest.dto.response;

import com.gyeot.domain.carerequest.enums.CareRequestStatus;

/**
 * 상태를 바꾼 뒤의 요청 상태 (data) - 돌봄 시작, 돌봄 완료
 * - status: 시작하면 IN_PROGRESS, 완료하면 COMPLETED
 */
public record RequestStatusResponse(
        Long requestId,
        CareRequestStatus status
) {
}