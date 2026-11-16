package com.gyeot.domain.situation.dto.response;

import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.PastRequestRecord;
import com.gyeot.domain.carerequest.query.RequestDisplayStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.entity.SituationStatus;

import java.time.LocalDateTime;
import java.util.List;

/** 지난 상황 기본 정보와 C가 조회자별로 필터링한 최소 요청 기록입니다. */
public record SituationHistoryDetailResponse(Info situation, List<RequestRecord> requests) {
    public static SituationHistoryDetailResponse from(Situation situation, List<PastRequestRecord> requests) {
        return new SituationHistoryDetailResponse(new Info(situation.getId(), situation.getTitle(),
                situation.getStatus(), situation.getEndedAt(), situation.getStartAt(), situation.getEndAt()), requests.stream().map(r ->
                new RequestRecord(r.requestId(), r.helpType(), r.status(), r.startsAt(), r.assigneeId())).toList());
    }

    /** 서울 현지 시각의 [startAt, endAt)을 보존하며 endedAt은 실제 종료 시각입니다. */
    public record Info(Long situationId, String name, SituationStatus status, LocalDateTime endedAt,
                       LocalDateTime startAt, LocalDateTime endAt) {}

    /** C startsAt과 실제 assigneeId의 의미를 유지하며 추가 개인정보는 포함하지 않습니다. */
    public record RequestRecord(Long requestId, HelpType helpType, RequestDisplayStatus status,
                                LocalDateTime startsAt, Long assigneeId) {}
}
