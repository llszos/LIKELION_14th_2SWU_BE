package com.gyeot.domain.situation.dto.internal;

import com.gyeot.domain.situation.entity.SituationParticipant;
import com.gyeot.domain.situation.entity.SituationParticipantRole;
import com.gyeot.domain.situation.entity.SituationParticipantStatus;

import java.time.LocalDateTime;

/**
 * 참여 이력 한 행의 내부 스냅샷입니다. 공개 HTTP 응답 계약이 아닙니다.
 * 조회 권한·노출 상태·정렬을 결정하지 않고 Entity 값을 그대로 매핑합니다.
 */
public record SituationParticipantInfo(
        Long participantId,
        Long userId,
        SituationParticipantRole role,
        SituationParticipantStatus status,
        LocalDateTime joinedAt,
        LocalDateTime leftAt
) {
    public static SituationParticipantInfo from(SituationParticipant participant) {
        return new SituationParticipantInfo(participant.getId(), participant.getUserId(),
                participant.getRole(), participant.getStatus(), participant.getJoinedAt(), participant.getLeftAt());
    }
}
