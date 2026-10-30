package com.gyeot.domain.situation.event;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * B가 참여 종료를 알리는 임시 계약입니다. C가 관련 요청·지정을 정리하고 후속 이벤트로 D의 권한 회수를 연결합니다.
 * TODO: LEFT/REMOVED 구분과 관계 해제 사유의 전달 방식은 C 담당자 확인 후 확정합니다.
 * @param situationId 참여가 종료된 상황 ID (situations.id)
 * @param userId 참여가 종료된 사용자 ID (users.id). 참여 행의 PK가 아닙니다.
 * @param occurredAt 참여 행에 기록한 종료 시각(leftAt), Asia/Seoul 기준
 */
public record SituationParticipantRemovedEvent(Long situationId, Long userId, LocalDateTime occurredAt) {

    public SituationParticipantRemovedEvent {
        Objects.requireNonNull(situationId, "situationId는 필수입니다.");
        Objects.requireNonNull(userId, "userId는 필수입니다.");
        Objects.requireNonNull(occurredAt, "occurredAt은 필수입니다.");
    }
}
