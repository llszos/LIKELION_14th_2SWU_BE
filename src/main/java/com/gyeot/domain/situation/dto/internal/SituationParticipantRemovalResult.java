package com.gyeot.domain.situation.dto.internal;

import com.gyeot.domain.situation.entity.SituationParticipant;
import com.gyeot.domain.situation.entity.SituationParticipantStatus;

import java.time.LocalDateTime;

/**
 * 제외 처리 후 참여 행의 내부 스냅샷입니다. HTTP 성공 여부나 커밋 완료를 의미하지 않습니다.
 * 실제 상태·시각을 보존하며 반복 요청의 응답 정책을 결정하지 않습니다.
 */
public record SituationParticipantRemovalResult(
        Long participantId,
        SituationParticipantStatus status,
        LocalDateTime leftAt
) {
    public static SituationParticipantRemovalResult from(SituationParticipant participant) {
        return new SituationParticipantRemovalResult(participant.getId(), participant.getStatus(), participant.getLeftAt());
    }
}
