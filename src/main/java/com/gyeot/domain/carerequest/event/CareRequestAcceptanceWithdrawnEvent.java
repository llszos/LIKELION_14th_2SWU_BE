package com.gyeot.domain.carerequest.event;

import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 수락 철회 (담당자가 시작 전에 철회)
 * - D: 해당 지정의 권한 회수
 * - B(알림): 요청자에게 철회 알림
 * 요청은 REQUESTED로 돌아가며, 요청자에게는 다음 연락 모달(F-14)이 뜹니다.
 */
public record CareRequestAcceptanceWithdrawnEvent(
        Long requestId,
        Long requesterId,
        Long assignmentId,
        Long assigneeId,
        LocalDateTime occurredAt
) implements CareRequestEvent {

    public CareRequestAcceptanceWithdrawnEvent {
        Objects.requireNonNull(requestId, "requestId는 필수입니다.");
        Objects.requireNonNull(requesterId, "requesterId는 필수입니다.");
        Objects.requireNonNull(assignmentId, "assignmentId는 필수입니다.");
        Objects.requireNonNull(assigneeId, "assigneeId는 필수입니다.");
        Objects.requireNonNull(occurredAt, "occurredAt은 필수입니다.");
    }

    // assignment.close(ACCEPTANCE_WITHDRAWN)와 careRequest.revertToRequested() 뒤에 호출합니다.
    public static CareRequestAcceptanceWithdrawnEvent of(RequestAssignment assignment, LocalDateTime occurredAt) {
        if (assignment.getCloseReason() != AssignmentCloseReason.ACCEPTANCE_WITHDRAWN) {
            throw new IllegalStateException("수락 철회로 마감된 지정이 아닙니다: " + assignment.getCloseReason());
        }
        return new CareRequestAcceptanceWithdrawnEvent(assignment.getCareRequest().getId(),
                assignment.getCareRequest().getRequesterId(), assignment.getId(), assignment.getTargetUserId(),
                occurredAt);
    }
}