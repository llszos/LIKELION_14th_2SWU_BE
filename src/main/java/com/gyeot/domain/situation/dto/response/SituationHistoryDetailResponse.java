package com.gyeot.domain.situation.dto.response;

import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.PastRequestRecord;
import com.gyeot.domain.carerequest.query.RequestDisplayStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.entity.SituationStatus;

import java.time.LocalDateTime;
import java.util.List;

/** 검토용 내부 DTO. 응답 필드·권한 합의 전에는 상세 HTTP API를 공개하지 않습니다. */
public record SituationHistoryDetailResponse(Info situation, List<RequestRecord> requests) {
    public static SituationHistoryDetailResponse from(Situation situation, List<PastRequestRecord> requests) {
        return new SituationHistoryDetailResponse(new Info(situation.getId(), situation.getTitle(),
                situation.getStatus(), situation.getEndedAt(), situation.getStartAt(), situation.getEndAt()), requests.stream().map(r ->
                new RequestRecord(r.requestId(), r.helpType(), r.status(), r.startsAt(), r.assigneeId())).toList());
    }

    /** 서울 현지 시각의 [startAt, endAt)을 보존하며 endedAt은 실제 종료 시각입니다. */
    public record Info(Long situationId, String name, SituationStatus status, LocalDateTime endedAt,
                       LocalDateTime startAt, LocalDateTime endAt) {}

    /** startsAt → scheduledAt은 API 초안에 맞춘 잠정 매핑입니다. */
    public record RequestRecord(Long requestId, HelpType helpType, RequestDisplayStatus status,
                                LocalDateTime scheduledAt, Long assigneeId) {}
}
