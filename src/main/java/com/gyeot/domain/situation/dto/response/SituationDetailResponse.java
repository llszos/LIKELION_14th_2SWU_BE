package com.gyeot.domain.situation.dto.response;

import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.entity.SituationStatus;

import java.time.LocalDateTime;

/** 진행 중 상황의 B 기본 상세입니다. 요청·통계·프로필은 아직 포함하지 않습니다. */
public record SituationDetailResponse(
        Long situationId,
        String name,
        String situationType,
        LocalDateTime startAt,
        LocalDateTime endAt,
        SituationStatus status,
        Long creatorId,
        LocalDateTime endedAt,
        String customSituationType
) {
    public static SituationDetailResponse from(Situation situation) {
        return new SituationDetailResponse(situation.getId(), situation.getTitle(), situation.getSituationType(),
                situation.getStartAt(), situation.getEndAt(), situation.getStatus(),
                situation.getCreatorId(), situation.getEndedAt(), situation.getCustomSituationType());
    }
}
