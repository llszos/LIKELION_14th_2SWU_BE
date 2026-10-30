package com.gyeot.domain.carerequest.event;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.enums.CancelReason;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 요청 취소 (요청자 취소·상황 종료·재확인 거절·진행 중 담당자 이탈·관계 해제·회원 탈퇴)
 * - D: 요청의 모든 ACTIVE·PENDING 권한 회수 (requestId 기준)
 * - B(알림): 취소 당시 지정되어 있던 대상자·담당자에게 취소 알림
 * - assignmentId·targetUserId: 취소 당시 열려 있던 지정. 없었으면 둘 다 null
 */
public record CareRequestCanceledEvent(
        Long requestId,
        Long requesterId,
        CancelReason cancelReason,
        Long assignmentId,
        Long targetUserId,
        LocalDateTime occurredAt
) implements CareRequestEvent {

    public CareRequestCanceledEvent {
        Objects.requireNonNull(requestId, "requestId는 필수입니다.");
        Objects.requireNonNull(requesterId, "requesterId는 필수입니다.");
        Objects.requireNonNull(cancelReason, "cancelReason은 필수입니다.");
        Objects.requireNonNull(occurredAt, "occurredAt은 필수입니다.");
        if ((assignmentId == null) != (targetUserId == null)) {
            throw new IllegalArgumentException("assignmentId와 targetUserId는 함께 있거나 함께 없어야 합니다.");
        }
    }

    /**
     * careRequest.cancel()과 지정 마감 뒤에 호출합니다.
     * 취소 당시 열린 지정이 없었으면 lastAssignment는 null입니다.
     */
    public static CareRequestCanceledEvent of(CareRequest careRequest, RequestAssignment lastAssignment,
                                              LocalDateTime occurredAt) {
        if (careRequest.getStatus() != CareRequestStatus.CANCELED) {
            throw new IllegalStateException("취소된 요청이 아닙니다: " + careRequest.getStatus());
        }
        Long assignmentId = lastAssignment == null ? null : lastAssignment.getId();
        Long targetUserId = lastAssignment == null ? null : lastAssignment.getTargetUserId();
        return new CareRequestCanceledEvent(careRequest.getId(), careRequest.getRequesterId(),
                careRequest.getCancelReason(), assignmentId, targetUserId, occurredAt);
    }
}