package com.gyeot.domain.situation.dto.response;

import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.RequestSummary;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.RequestType;
import com.gyeot.domain.carerequest.query.RequestDisplayStatus;
import com.gyeot.domain.user.query.UserProfileQuery.UserProfile;
import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.entity.SituationStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** B 기본 정보와 C의 조회자별 요청 목록·상황 전체 미종료 요청 수입니다. */
public record SituationDetailResponse(
        Long situationId,
        String name,
        String situationType,
        LocalDateTime startAt,
        LocalDateTime endAt,
        SituationStatus status,
        Long creatorId,
        Long viewerId,
        boolean isOwner,
        LocalDateTime endedAt,
        String customSituationType,
        int activeRequestCount,
        List<RequestItem> requests
) {
    public SituationDetailResponse {
        requests = List.copyOf(requests);
    }

    public static SituationDetailResponse from(Situation situation, Long viewerId, int activeRequestCount,
                                               List<RequestSummary> requests, Map<Long, UserProfile> profiles) {
        return new SituationDetailResponse(situation.getId(), situation.getTitle(), situation.getSituationType(),
                situation.getStartAt(), situation.getEndAt(), situation.getStatus(),
                situation.getCreatorId(), viewerId, situation.getCreatorId().equals(viewerId),
                situation.getEndedAt(), situation.getCustomSituationType(), activeRequestCount,
                requests.stream().map(r -> RequestItem.from(r, profiles)).toList());
    }

    public record RequestItem(Long requestId, RequestType requestType, HelpType helpType,
                              LocalDateTime startsAt, LocalDateTime endsAt, Long requesterId,
                              Long targetUserId, RequestDisplayStatus displayStatus, String targetUserName) {
        private static RequestItem from(RequestSummary request, Map<Long, UserProfile> profiles) {
            String name = null;
            if (request.targetUserId() != null) {
                name = switch (request.displayStatus()) {
                    case WAITING, ACCEPTED, IN_PROGRESS, COMPLETED -> displayName(profiles.get(request.targetUserId()));
                    default -> null;
                };
            }
            return new RequestItem(request.requestId(), request.requestType(), request.helpType(), request.startsAt(),
                    request.endsAt(), request.requesterId(), request.targetUserId(), request.displayStatus(), name);
        }

        private static String displayName(UserProfile profile) {
            if (profile == null) return null;
            return profile.withdrawn() ? "탈퇴한 사용자" : profile.name();
        }
    }
}
