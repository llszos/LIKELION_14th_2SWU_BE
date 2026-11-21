package com.gyeot.domain.situation.dto.response;

import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.PastRequestRecord;
import com.gyeot.domain.carerequest.query.RequestDisplayStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.entity.SituationStatus;
import com.gyeot.domain.user.query.UserProfileQuery.UserProfile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 지난 상황 기본 정보와 C가 조회자별로 필터링한 최소 요청 기록입니다. */
public record SituationHistoryDetailResponse(Info situation, List<RequestRecord> requests) {
    public static SituationHistoryDetailResponse from(Situation situation, Long viewerId, List<PastRequestRecord> requests,
                                                      Map<Long, UserProfile> profiles) {
        return new SituationHistoryDetailResponse(new Info(situation.getId(), situation.getTitle(),
                situation.getStatus(), situation.getEndedAt(), situation.getStartAt(), situation.getEndAt(),
                situation.getCreatorId().equals(viewerId)), requests.stream().map(r ->
                new RequestRecord(r.requestId(), r.helpType(), r.status(), r.startsAt(), r.endsAt(),
                        r.assigneeId(), displayName(r.assigneeId(), profiles))).toList());
    }

    private static String displayName(Long userId, Map<Long, UserProfile> profiles) {
        UserProfile profile = userId == null ? null : profiles.get(userId);
        if (profile == null) return null;
        return profile.withdrawn() ? "탈퇴한 사용자" : profile.name();
    }

    /** 서울 현지 시각의 [startAt, endAt)을 보존하며 endedAt은 실제 종료 시각입니다. */
    public record Info(Long situationId, String name, SituationStatus status, LocalDateTime endedAt,
                       LocalDateTime startAt, LocalDateTime endAt, boolean isOwner) {}

    /** C의 예정 시작·종료 시각과 실제 담당자 정보를 보존합니다. */
    public record RequestRecord(Long requestId, HelpType helpType, RequestDisplayStatus status,
                                LocalDateTime startsAt, LocalDateTime endsAt, Long assigneeId, String assigneeName) {}
}
