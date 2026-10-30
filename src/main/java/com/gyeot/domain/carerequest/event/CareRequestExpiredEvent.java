package com.gyeot.domain.carerequest.event;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 요청 만료 (일반 요청이 시작 시각까지 담당자 없음, 스케줄러 처리)
 * - D: 요청의 PENDING 권한 회수
 * - B(알림): 요청자와 만료 당시 대상자에게 만료 알림
 * - assignmentId·targetUserId: 만료 당시 열려 있던 지정. 없었으면 둘 다 null
 */
public record CareRequestExpiredEvent(
        Long requestId,
        Long requesterId,
        Long assignmentId,
        Long targetUserId,
        LocalDateTime occurredAt
) implements CareRequestEvent {

    public CareRequestExpiredEvent {
        Objects.requireNonNull(requestId, "requestId는 필수입니다.");
        Objects.requireNonNull(requesterId, "requesterId는 필수입니다.");
        Objects.requireNonNull(occurredAt, "occurredAt은 필수입니다.");
        if ((assignmentId == null) != (targetUserId == null)) {
            throw new IllegalArgumentException("assignmentId와 targetUserId는 함께 있거나 함께 없어야 합니다.");
        }
    }

    /** careRequest.expire()와 지정 마감 뒤에 호출합니다.
     * 만료 당시 열린 지정이 없었으면 lastAssignment는 null입니다.
     */
    public static CareRequestExpiredEvent of(CareRequest careRequest, RequestAssignment lastAssignment,
                                             LocalDateTime occurredAt) {
        if (careRequest.getStatus() != CareRequestStatus.EXPIRED) {
            throw new IllegalStateException("만료된 요청이 아닙니다: " + careRequest.getStatus());
        }
        Long assignmentId = lastAssignment == null ? null : lastAssignment.getId();
        Long targetUserId = lastAssignment == null ? null : lastAssignment.getTargetUserId();
        return new CareRequestExpiredEvent(careRequest.getId(), careRequest.getRequesterId(),
                assignmentId, targetUserId, occurredAt);
    }
}