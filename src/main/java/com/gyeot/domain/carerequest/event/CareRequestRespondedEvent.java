package com.gyeot.domain.carerequest.event;

import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 응답 (어려움 / 확인 후 답변)
 * - D: 처리 없음
 * - B(알림): 요청자에게 응답 알림
 * '도움 가능'은 수락이므로 이 이벤트가 아니라 CareRequestAcceptedEvent로 발행합니다. (알림 중복 방지)
 */
public record CareRequestRespondedEvent(
        Long requestId,
        Long requesterId,
        Long assignmentId,
        Long targetUserId,
        AssignmentResponseStatus responseStatus,
        LocalDateTime occurredAt
) implements CareRequestEvent {

    public CareRequestRespondedEvent {
        Objects.requireNonNull(requestId, "requestId는 필수입니다.");
        Objects.requireNonNull(requesterId, "requesterId는 필수입니다.");
        Objects.requireNonNull(assignmentId, "assignmentId는 필수입니다.");
        Objects.requireNonNull(targetUserId, "targetUserId는 필수입니다.");
        Objects.requireNonNull(responseStatus, "responseStatus는 필수입니다.");
        Objects.requireNonNull(occurredAt, "occurredAt은 필수입니다.");
        if (responseStatus == AssignmentResponseStatus.AVAILABLE) {
            throw new IllegalArgumentException("도움 가능 응답은 CareRequestAcceptedEvent로 발행합니다.");
        }
    }

    // assignment.respond() 뒤에 호출합니다. 응답은 지정에서 직접 읽습니다.
    public static CareRequestRespondedEvent of(RequestAssignment assignment, LocalDateTime occurredAt) {
        return new CareRequestRespondedEvent(assignment.getCareRequest().getId(),
                assignment.getCareRequest().getRequesterId(), assignment.getId(), assignment.getTargetUserId(),
                assignment.getResponseStatus(), occurredAt);
    }
}