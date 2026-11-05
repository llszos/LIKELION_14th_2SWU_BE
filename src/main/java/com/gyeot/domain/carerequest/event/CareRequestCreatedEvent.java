package com.gyeot.domain.carerequest.event;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.enums.RequestType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 요청 도착 (요청 생성)
 * - D: 공유 예정(PENDING) 권한 생성 (assignmentId + informationIds)
 * - B(알림): 대상자에게 요청 도착 알림 (급한 요청이면 급한 요청 도착)
 * informationIds는 요청자가 공개 정보 미리보기에서 확정한 안전정보 ID 목록입니다. 공개 항목이 없으면 빈 목록입니다.
 */
public record CareRequestCreatedEvent(
        Long requestId,
        Long requesterId,
        RequestType requestType,
        Long assignmentId,
        Long targetUserId,
        List<Long> informationIds,
        LocalDateTime occurredAt
) implements CareRequestEvent {

    public CareRequestCreatedEvent {
        Objects.requireNonNull(requestId, "requestId는 필수입니다.");
        Objects.requireNonNull(requesterId, "requesterId는 필수입니다.");
        Objects.requireNonNull(requestType, "requestType은 필수입니다.");
        Objects.requireNonNull(assignmentId, "assignmentId는 필수입니다.");
        Objects.requireNonNull(targetUserId, "targetUserId는 필수입니다.");
        Objects.requireNonNull(occurredAt, "occurredAt은 필수입니다.");
        informationIds = informationIds == null ? List.of() : List.copyOf(informationIds);
    }

    // 요청과 최초 지정을 save()한 뒤에 호출합니다.
    public static CareRequestCreatedEvent of(RequestAssignment assignment, List<Long> informationIds,
                                             LocalDateTime occurredAt) {
        CareRequest request = assignment.getCareRequest();
        return new CareRequestCreatedEvent(request.getId(), request.getRequesterId(), request.getRequestType(),
                assignment.getId(), assignment.getTargetUserId(), informationIds, occurredAt);
    }
}