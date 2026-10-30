package com.gyeot.domain.carerequest.event;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.enums.RequestType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 대상 변경 (다시 부탁하기)
 * - D: 이전 지정(previousAssignmentId)의 권한 회수, 새 지정(assignmentId)에 공유 예정(PENDING) 권한 생성
 * - B(알림): 새 대상자에게 요청 도착 알림
 * informationIds는 다시 부탁하기의 공개 정보 미리보기에서 확정한 안전정보 ID 목록입니다.
 */
public record CareRequestTargetChangedEvent(
        Long requestId,
        Long requesterId,
        RequestType requestType,
        Long previousAssignmentId,
        Long previousTargetUserId,
        Long assignmentId,
        Long targetUserId,
        List<Long> informationIds,
        LocalDateTime occurredAt
) implements CareRequestEvent {

    public CareRequestTargetChangedEvent {
        Objects.requireNonNull(requestId, "requestId는 필수입니다.");
        Objects.requireNonNull(requesterId, "requesterId는 필수입니다.");
        Objects.requireNonNull(requestType, "requestType은 필수입니다.");
        Objects.requireNonNull(previousAssignmentId, "previousAssignmentId는 필수입니다.");
        Objects.requireNonNull(previousTargetUserId, "previousTargetUserId는 필수입니다.");
        Objects.requireNonNull(assignmentId, "assignmentId는 필수입니다.");
        Objects.requireNonNull(targetUserId, "targetUserId는 필수입니다.");
        Objects.requireNonNull(occurredAt, "occurredAt은 필수입니다.");
        informationIds = informationIds == null ? List.of() : List.copyOf(informationIds);
    }

    // 이전 지정을 마감하고 새 지정을 save()한 뒤에 호출합니다.
    public static CareRequestTargetChangedEvent of(RequestAssignment previous, RequestAssignment next,
                                                   List<Long> informationIds, LocalDateTime occurredAt) {
        if (!Objects.equals(previous.getCareRequest().getId(), next.getCareRequest().getId())) {
            throw new IllegalArgumentException("이전 지정과 새 지정은 같은 요청에 속해야 합니다.");
        }
        if (previous.isOpen()) {
            throw new IllegalStateException("이전 지정을 먼저 마감해야 합니다.");
        }
        CareRequest request = next.getCareRequest();
        return new CareRequestTargetChangedEvent(request.getId(), request.getRequesterId(), request.getRequestType(),
                previous.getId(), previous.getTargetUserId(), next.getId(), next.getTargetUserId(),
                informationIds, occurredAt);
    }
}
