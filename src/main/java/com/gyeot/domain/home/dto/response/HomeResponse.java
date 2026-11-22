package com.gyeot.domain.home.dto.response;

import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.HomeRequestOverview;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.ResponseNeededType;
import com.gyeot.domain.carerequest.query.RequestDisplayStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.RequestType;

import com.gyeot.domain.situation.service.SituationCardActivity.NextSchedule;
import com.gyeot.domain.situation.service.SituationCardActivity.RequestCounts;

import java.time.LocalDateTime;
import com.gyeot.domain.user.query.UserProfileQuery.UserProfile;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 홈 화면의 요청·일정과 요약 건수입니다. */
public record HomeResponse(Counts counts, List<ResponseNeededItem> responseNeededItems,
                           List<RequestSummary> sentOpenItems, List<UpcomingSchedule> upcomingSchedules,
                           SituationCards situationCards, boolean isNewUser) {
    public HomeResponse {
        Objects.requireNonNull(counts, "홈 건수는 필수입니다.");
        responseNeededItems = List.copyOf(responseNeededItems);
        sentOpenItems = List.copyOf(sentOpenItems);
        upcomingSchedules = List.copyOf(upcomingSchedules);
    }

    public static HomeResponse from(HomeRequestOverview overview, long activeSharings,
                                    List<CareRequestSummaryReader.UpcomingSchedule> upcoming, Long viewerId,
                                    Map<Long, UserProfile> profiles, SituationCards situationCards, boolean isNewUser) {
        Objects.requireNonNull(overview, "C 홈 조회 결과는 필수입니다.");
        return new HomeResponse(new Counts(overview.responseNeededCount(), overview.noAssigneeCount(),
                overview.upcomingCount(), activeSharings),
                overview.responseNeeded().stream()
                        .map(item -> ResponseNeededItem.from(item, name(profiles, item.requesterId()))).toList(),
                overview.sentRequests().stream()
                        .map(item -> RequestSummary.from(item, name(profiles, displayTargetId(item)))).toList(),
                upcoming.stream()
                        .map(item -> UpcomingSchedule.from(item, name(profiles, counterpartId(item, viewerId))))
                        .toList(), situationCards, isNewUser);
    }

    public static Long displayTargetId(CareRequestSummaryReader.RequestSummary item) {
        return item.displayStatus() == RequestDisplayStatus.NO_ASSIGNEE ? null : item.targetUserId();
    }

    public static Long counterpartId(CareRequestSummaryReader.UpcomingSchedule item, Long viewerId) {
        if (viewerId.equals(item.requesterId())) {
            return item.assigneeId();
        }
        return viewerId.equals(item.assigneeId()) ? item.requesterId() : null;
    }

    public static String name(Map<Long, UserProfile> profiles, Long userId) {
        var profile = userId == null ? null : profiles.get(userId);
        if (profile == null) {
            return null;
        }
        return profile.withdrawn() ? "탈퇴한 사용자" : profile.name();
    }

    public record SituationCards(List<SituationCard> my, List<SituationCard> participating) {}

    public record SituationCard(Long situationId, String name, LocalDateTime startAt, LocalDateTime endAt,
                                long participantCount, boolean isOwner, NextSchedule nextSchedule,
                                int remainingScheduleCount, RequestCounts requestCounts) {}

    /** upcomingSchedules는 표시 목록 크기가 아닌 C가 제공하는 전체 예정 일정 수입니다. */
    public record Counts(int responseNeeded, int unassigned, int upcomingSchedules, long activeSharings) {}

    public record ResponseNeededItem(Long requestId, ResponseNeededType type, RequestType requestType,
                                     HelpType helpType, LocalDateTime startsAt, String place,
                                     Long requesterId, LocalDateTime deadline, String requesterName) {
        private static ResponseNeededItem from(CareRequestSummaryReader.ResponseNeededItem item, String requesterName) {
            return new ResponseNeededItem(item.requestId(), item.type(), item.requestType(), item.helpType(),
                    item.startsAt(), item.place(), item.requesterId(), item.deadline(), requesterName);
        }
    }

    public record RequestSummary(Long requestId, RequestType requestType, HelpType helpType,
                                 LocalDateTime startsAt, LocalDateTime endsAt, Long requesterId,
                                 Long targetUserId, RequestDisplayStatus displayStatus, String targetUserName,
                                 LocalDateTime responseDeadline) {
        private static RequestSummary from(CareRequestSummaryReader.RequestSummary item, String targetUserName) {
            return new RequestSummary(item.requestId(), item.requestType(), item.helpType(), item.startsAt(),
                    item.endsAt(), item.requesterId(), item.targetUserId(), item.displayStatus(), targetUserName,
                    item.responseDeadline());
        }
    }

    public record UpcomingSchedule(Long requestId, HelpType helpType, LocalDateTime startsAt,
                                   String place, Long requesterId, Long assigneeId, String counterpartName) {
        private static UpcomingSchedule from(CareRequestSummaryReader.UpcomingSchedule item, String counterpartName) {
            return new UpcomingSchedule(item.requestId(), item.helpType(), item.startsAt(), item.place(),
                    item.requesterId(), item.assigneeId(), counterpartName);
        }
    }
}
