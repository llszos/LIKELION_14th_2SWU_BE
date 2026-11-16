package com.gyeot.domain.situation.dto.response;

import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.RequestSummary;
import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.entity.SituationStatus;

import java.time.LocalDateTime;
import java.util.List;

/** B 기본 정보와 C의 조회자별 요청 목록·상황 전체 미종료 요청 수입니다. */
public record SituationDetailResponse(
        Long situationId,
        String name,
        String situationType,
        LocalDateTime startAt,
        LocalDateTime endAt,
        SituationStatus status,
        Long creatorId,
        LocalDateTime endedAt,
        String customSituationType,
        int activeRequestCount,
        List<RequestSummary> requests
) {
    public SituationDetailResponse {
        requests = List.copyOf(requests);
    }

    public static SituationDetailResponse from(Situation situation, int activeRequestCount,
                                               List<RequestSummary> requests) {
        return new SituationDetailResponse(situation.getId(), situation.getTitle(), situation.getSituationType(),
                situation.getStartAt(), situation.getEndAt(), situation.getStatus(),
                situation.getCreatorId(), situation.getEndedAt(), situation.getCustomSituationType(),
                activeRequestCount, requests);
    }
}
