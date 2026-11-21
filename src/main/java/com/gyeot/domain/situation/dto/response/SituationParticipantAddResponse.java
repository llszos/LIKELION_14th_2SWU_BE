package com.gyeot.domain.situation.dto.response;

import com.gyeot.domain.situation.entity.*;
import java.time.LocalDateTime;

public record SituationParticipantAddResponse(Long participantId, Long situationId, Long userId,
        SituationParticipantRole role, SituationParticipantStatus status, LocalDateTime joinedAt) {
    public static SituationParticipantAddResponse from(SituationParticipant p) {
        return new SituationParticipantAddResponse(p.getId(), p.getSituation().getId(), p.getUserId(),
                p.getRole(), p.getStatus(), p.getJoinedAt());
    }
}
