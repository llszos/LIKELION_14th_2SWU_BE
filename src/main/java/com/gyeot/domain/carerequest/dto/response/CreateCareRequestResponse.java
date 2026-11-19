package com.gyeot.domain.carerequest.dto.response;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;

import java.time.LocalDateTime;

/**
 * 도움 요청 생성 응답 (POST /api/care-requests)
 * - requestId: 요청 상세로 이동할 때 사용
 * - status: 항상 REQUESTED
 * - responseDeadline: 대상자의 응답 기한 (시작 시각으로 제한된 경우 시작 시각)
 */
public record CreateCareRequestResponse(
        Long requestId,
        CareRequestStatus status,
        LocalDateTime responseDeadline
) {
    public static CreateCareRequestResponse of(CareRequest request, RequestAssignment assignment) {
        return new CreateCareRequestResponse(request.getId(), request.getStatus(), assignment.getResponseDeadline());
    }
}
