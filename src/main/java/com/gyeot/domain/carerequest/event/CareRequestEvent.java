package com.gyeot.domain.carerequest.event;

import java.time.LocalDateTime;

/**
 * 도움 요청(C) 영역이 발행하는 이벤트의 공통 타입입니다.
 * - 다른 영역(D 권한, B 알림)은 필요한 이벤트만 골라 받거나, 이 타입 하나로 모두 받을 수 있습니다.
 * - 엔티티 대신 ID만 담습니다.
 *
 * 발행 규칙 (팀 이벤트 처리 규칙 4장 참조)
 * - @Transactional 서비스 안에서 엔티티 상태를 바꾸고 save()한 뒤에 발행합니다.
 * - 한 동작에서 요청이 끝나면(취소·완료·만료) 지정 마감 이벤트는 따로 발행하지 않고 요청 종료 이벤트만 발행합니다.
 * - DB 이력(request_change_events 엔티티)과는 별개이고, 이 이벤트는 다른 영역에 알리는 용도입니다.
 */
public sealed interface CareRequestEvent permits
        CareRequestCreatedEvent, CareRequestTargetChangedEvent, CareRequestRespondedEvent,
        CareRequestAcceptedEvent, CareRequestAcceptanceWithdrawnEvent, CareRequestAssignmentClosedEvent,
        CareRequestChangeProposedEvent, CareRequestChangeConfirmedEvent,
        CareRequestCanceledEvent, CareRequestCompletedEvent, CareRequestExpiredEvent {

    Long requestId();

    Long requesterId();

    LocalDateTime occurredAt();
}