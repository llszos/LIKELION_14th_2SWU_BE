package com.gyeot.domain.situation.dto.response;

import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.entity.SituationStatus;

import java.time.LocalDateTime;

public record SituationEndResponse(Long situationId, SituationStatus status, LocalDateTime endedAt) {
    public static SituationEndResponse from(Situation situation) {
        return new SituationEndResponse(situation.getId(), situation.getStatus(), situation.getEndedAt());
    }
}
