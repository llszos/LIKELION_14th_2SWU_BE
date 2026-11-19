package com.gyeot.domain.carerequest.dto.response;

import com.gyeot.domain.carerequest.entity.RequestAssignment;

import java.time.LocalDateTime;

/**
 * 다시 부탁하기 응답 (POST /api/care-requests/{requestId}/assignments)
 * - assignmentId: 새 대상 지정 ID
 * - responseDeadline: 새 대상의 응답 기한 (시작 시각으로 제한된 경우 시작 시각)
 */
public record ReassignResponse(Long requestId, Long assignmentId, LocalDateTime responseDeadline) {

    public static ReassignResponse of(RequestAssignment assignment) {
        return new ReassignResponse(assignment.getCareRequest().getId(), assignment.getId(),
                assignment.getResponseDeadline());
    }
}
