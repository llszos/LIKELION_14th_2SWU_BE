package com.gyeot.domain.carerequest.dto.response;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;

/**
 * 요청 응답 결과 (data)입니다.
 * - status: 요청 상태. 수락하면 ACCEPTED, 그 외 REQUESTED
 * - responseStatus: 내 응답
 */

public record RespondResponse(
        Long requestId,
        CareRequestStatus status,
        AssignmentResponseStatus responseStatus
) {
    public static RespondResponse of(CareRequest request, RequestAssignment assignment) {
        return new RespondResponse(request.getId(), request.getStatus(), assignment.getResponseStatus());
    }

    /** 수락했는지 (응답 message를 정할 때 사용) */
    public boolean accepted() {
        return responseStatus == AssignmentResponseStatus.AVAILABLE;
    }
}
