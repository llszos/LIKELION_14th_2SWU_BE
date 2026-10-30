package com.gyeot.domain.carerequest.event;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.enums.RequestType;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 수락 ('도움 가능' 응답)
 * - D: 해당 지정의 공유 예정(PENDING) 권한을 활성화(ACTIVE)
 *      급한 요청(URGENT)이면 expires_at = occurredAt + 24시간 (ERD 4.11)
 * - B(알림): 요청자에게 수락 알림
 */
public record CareRequestAcceptedEvent(
        Long requestId,
        Long requesterId,
        RequestType requestType,
        Long assignmentId,
        Long assigneeId,
        LocalDateTime occurredAt
) implements CareRequestEvent {

    public CareRequestAcceptedEvent {
        Objects.requireNonNull(requestId, "requestId는 필수입니다.");
        Objects.requireNonNull(requesterId, "requesterId는 필수입니다.");
        Objects.requireNonNull(requestType, "requestType은 필수입니다.");
        Objects.requireNonNull(assignmentId, "assignmentId는 필수입니다.");
        Objects.requireNonNull(assigneeId, "assigneeId는 필수입니다.");
        Objects.requireNonNull(occurredAt, "occurredAt은 필수입니다.");
    }

    // assignment.respond(AVAILABLE)와 careRequest.accept() 뒤에 호출합니다.
    public static CareRequestAcceptedEvent of(RequestAssignment assignment, LocalDateTime occurredAt) {
        if (!assignment.isCurrentAssignee()) {
            throw new IllegalStateException("수락된 현재 지정이 아닙니다.");
        }
        CareRequest request = assignment.getCareRequest();
        return new CareRequestAcceptedEvent(request.getId(), request.getRequesterId(), request.getRequestType(),
                assignment.getId(), assignment.getTargetUserId(), occurredAt);
    }
}