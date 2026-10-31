package com.gyeot.domain.carerequest.event;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 요청 완료
 * - D: 요청의 권한 회수
 * - B(알림): 완료를 누르지 않은 쪽에게 완료 알림 (요청자가 완료 → 담당자, 담당자가 완료 → 요청자)
 * - completedByUserId: 완료를 누른 사용자. 종료 시각 도달로 스케줄러가 처리하면 null
 */
public record CareRequestCompletedEvent(
        Long requestId,
        Long requesterId,
        Long assignmentId,
        Long assigneeId,
        Long completedByUserId,
        LocalDateTime occurredAt
) implements CareRequestEvent {

    public CareRequestCompletedEvent {
        Objects.requireNonNull(requestId, "requestId는 필수입니다.");
        Objects.requireNonNull(requesterId, "requesterId는 필수입니다.");
        Objects.requireNonNull(assignmentId, "assignmentId는 필수입니다.");
        Objects.requireNonNull(assigneeId, "assigneeId는 필수입니다.");
        Objects.requireNonNull(occurredAt, "occurredAt은 필수입니다.");
    }

    /** careRequest.complete() 뒤에 호출합니다.
     * assignment는 완료한 담당자의 지정입니다.
     * */
    public static CareRequestCompletedEvent of(CareRequest careRequest, RequestAssignment assignment,
                                               Long completedByUserId, LocalDateTime occurredAt) {
        if (careRequest.getStatus() != CareRequestStatus.COMPLETED) {
            throw new IllegalStateException("완료된 요청이 아닙니다: " + careRequest.getStatus());
        }
        return new CareRequestCompletedEvent(careRequest.getId(), careRequest.getRequesterId(),
                assignment.getId(), assignment.getTargetUserId(), completedByUserId, occurredAt);
    }
}