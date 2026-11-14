package com.gyeot.domain.situation.dto.response;

import com.gyeot.domain.situation.entity.SituationParticipantStatus;

import java.time.LocalDateTime;

/** 최초 이탈 시각과 현재 참여 종료 상태를 반환합니다. 반복 요청에서는 REMOVED일 수도 있습니다. */
public record SituationLeaveResponse(Long situationId, Long userId,
        SituationParticipantStatus status, LocalDateTime leftAt) {
}
