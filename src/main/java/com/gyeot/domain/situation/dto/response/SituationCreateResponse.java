package com.gyeot.domain.situation.dto.response;

import com.gyeot.domain.situation.entity.SituationStatus;

/** 상황 생성 결과입니다. status는 JSON에서 기존 enum 이름 문자열로 반환합니다. */
public record SituationCreateResponse(
        Long situationId,
        Long creatorId,
        SituationStatus status
) {
}
