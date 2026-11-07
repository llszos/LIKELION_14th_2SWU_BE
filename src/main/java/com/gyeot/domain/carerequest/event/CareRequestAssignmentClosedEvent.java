package com.gyeot.domain.carerequest.event;

import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;

/**
 * 대상 지정 마감 (관계 해제·상황 제외·회원 탈퇴·급한 요청 응답 기한 경과)
 * - D: 해당 지정의 권한 회수
 * - B(알림): 없음 (요청자에게는 알림 대신 다음 연락 모달 F-14)

 * 아래 사유는 다른 이벤트가 같은 사실을 알리므로 이 이벤트로 발행하지 않았습니다.
 * - TARGET_CHANGED → CareRequestTargetChangedEvent
 * - ACCEPTANCE_WITHDRAWN → CareRequestAcceptanceWithdrawnEvent
 * - REQUEST_ENDED → 취소·완료·만료 이벤트
 */
public record CareRequestAssignmentClosedEvent(
        Long requestId,
        Long requesterId,
        Long assignmentId,
        Long targetUserId,
        AssignmentCloseReason closeReason,
        LocalDateTime occurredAt
) implements CareRequestEvent {

    private static final Set<AssignmentCloseReason> COVERED_BY_OTHER_EVENTS = Set.of(
            AssignmentCloseReason.TARGET_CHANGED,
            AssignmentCloseReason.ACCEPTANCE_WITHDRAWN,
            AssignmentCloseReason.REQUEST_ENDED);

    public CareRequestAssignmentClosedEvent {
        Objects.requireNonNull(requestId, "requestId는 필수입니다.");
        Objects.requireNonNull(requesterId, "requesterId는 필수입니다.");
        Objects.requireNonNull(assignmentId, "assignmentId는 필수입니다.");
        Objects.requireNonNull(targetUserId, "targetUserId는 필수입니다.");
        Objects.requireNonNull(closeReason, "closeReason은 필수입니다.");
        Objects.requireNonNull(occurredAt, "occurredAt은 필수입니다.");
        if (COVERED_BY_OTHER_EVENTS.contains(closeReason)) {
            throw new IllegalArgumentException("이 마감 사유는 다른 이벤트로 발행합니다: " + closeReason);
        }
    }

    // assignment.close() 뒤에 호출합니다. 마감 사유는 지정에서 직접 읽습니다.
    public static CareRequestAssignmentClosedEvent of(RequestAssignment assignment, LocalDateTime occurredAt) {
        return new CareRequestAssignmentClosedEvent(assignment.getCareRequest().getId(),
                assignment.getCareRequest().getRequesterId(), assignment.getId(), assignment.getTargetUserId(),
                assignment.getCloseReason(), occurredAt);
    }
}