package com.gyeot.domain.situation.event;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * B가 참여 종료를 알리는 이벤트입니다. C가 관련 요청·지정을 정리하고 후속 이벤트로 D의 권한 회수를 연결합니다.
 * 자진 이탈·직접 제외는 PARTICIPANT_LEFT, 관계 해제로 인한 제외는 RELATIONSHIP_ENDED로 전달합니다.
 * DB 참여 상태 LEFT·REMOVED와 이벤트 원인은 별개입니다.
 * @param situationId 참여가 종료된 상황 ID (situations.id)
 * @param userId 참여가 종료된 사용자 ID (users.id). 참여 행의 PK가 아닙니다.
 * @param reason C가 후속 처리에 사용할 참여 종료 원인
 * @param occurredAt 참여 행에 기록한 종료 시각(leftAt), Asia/Seoul 기준
 */
public record SituationParticipantRemovedEvent(Long situationId, Long userId,
        SituationParticipantRemovalReason reason, LocalDateTime occurredAt) {

    public SituationParticipantRemovedEvent {
        Objects.requireNonNull(situationId, "situationId는 필수입니다.");
        Objects.requireNonNull(userId, "userId는 필수입니다.");
        Objects.requireNonNull(reason, "reason은 필수입니다.");
        Objects.requireNonNull(occurredAt, "occurredAt은 필수입니다.");
    }
}
