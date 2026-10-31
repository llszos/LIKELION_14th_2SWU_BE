package com.gyeot.domain.situation.event;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * B가 상황 종료를 알리는 이벤트입니다. C가 관련 요청을 취소하고 후속 이벤트로 D의 권한 회수를 연결합니다.
 * @param situationId 종료된 상황 ID (situations.id)
 * @param occurredAt 상황에 기록한 실제 종료 시각(endedAt), Asia/Seoul 기준
 */
public record SituationEndedEvent(Long situationId, LocalDateTime occurredAt) {

    public SituationEndedEvent {
        Objects.requireNonNull(situationId, "situationId는 필수입니다.");
        Objects.requireNonNull(occurredAt, "occurredAt은 필수입니다.");
    }
}
