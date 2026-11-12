package com.gyeot.domain.situation.dto.response;

import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.PastRequestRecord;
import com.gyeot.domain.carerequest.query.RequestDisplayStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.entity.SituationStatus;

import java.time.LocalDateTime;
import java.util.List;

/** 내부 DTO. C 필드 매핑 검토 전까지 상세 HTTP API는 공개하지 않습니다. */
public record SituationHistoryDetailResponse(Info situation, List<RequestRecord> requests) {
    public static SituationHistoryDetailResponse from(Situation situation, List<PastRequestRecord> requests) {
        return new SituationHistoryDetailResponse(new Info(situation.getId(), situation.getTitle(),
                situation.getStatus(), situation.getEndedAt()), requests.stream().map(r ->
                new RequestRecord(r.requestId(), r.helpType(), r.status(), r.startsAt(), r.assigneeId())).toList());
    }

    public record Info(Long situationId, String name, SituationStatus status, LocalDateTime endedAt) {}

    /** startsAt → scheduledAt은 API 초안에 맞춘 잠정 매핑입니다. */
    public record RequestRecord(Long requestId, HelpType helpType, RequestDisplayStatus status,
                                LocalDateTime scheduledAt, Long assigneeId) {}
}
