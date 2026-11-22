package com.gyeot.domain.situation.service;

import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.ResponseNeededItem;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.SituationRequestStats;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.UpcomingSchedule;
import com.gyeot.domain.user.query.UserProfileQuery.UserProfile;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 홈과 진행 중 목록이 공유하는 조회자별 카드 집계입니다. */
public record SituationCardActivity(SituationRequestStats stats, long responseNeeded) {

    public static Map<Long, SituationCardActivity> load(
            Map<Long, Long> creatorsBySituation, Long viewerId, LocalDateTime now,
            CareRequestSummaryReader reader, Collection<ResponseNeededItem> responseNeeded) {
        var ownedIds = creatorsBySituation.entrySet().stream()
                .filter(entry -> viewerId.equals(entry.getValue())).map(Map.Entry::getKey).toList();
        var ownedStats = ownedIds.isEmpty() ? Map.<Long, SituationRequestStats>of()
                : reader.getSituationStats(ownedIds, now);
        var neededBySituation = responseNeeded.stream().filter(item -> item.situationId() != null)
                .collect(Collectors.groupingBy(ResponseNeededItem::situationId, Collectors.counting()));
        return creatorsBySituation.keySet().stream().collect(Collectors.toMap(Function.identity(), id -> {
            var stats = viewerId.equals(creatorsBySituation.get(id))
                    ? ownedStats.getOrDefault(id, SituationRequestStats.empty())
                    : participatingStats(reader, id, viewerId, now);
            return new SituationCardActivity(stats, neededBySituation.getOrDefault(id, 0L));
        }));
    }

    private static SituationRequestStats participatingStats(
            CareRequestSummaryReader reader, Long situationId, Long viewerId, LocalDateTime now) {
        UpcomingSchedule next = null;
        int accepted = 0;
        int inProgress = 0;
        for (var request : reader.getSituationRequests(situationId, viewerId, now)) {
            if (!viewerId.equals(request.targetUserId())) {
                continue;
            }
            switch (request.displayStatus()) {
                case ACCEPTED -> accepted++;
                case IN_PROGRESS -> inProgress++;
                default -> { continue; }
            }
            // C 목록은 시작 시각순입니다.
            if (next == null) {
                next = new UpcomingSchedule(request.requestId(), request.helpType(), request.startsAt(),
                        null, request.requesterId(), request.targetUserId());
            }
        }
        return new SituationRequestStats(accepted, inProgress, 0, 0, next);
    }

    public Long nextAssigneeId() {
        return stats.nextSchedule() == null ? null : stats.nextSchedule().assigneeId();
    }

    public NextSchedule nextSchedule(Map<Long, UserProfile> profiles) {
        var next = stats.nextSchedule();
        if (next == null) {
            return null;
        }
        var id = next.assigneeId();
        var profile = id == null ? null : profiles.get(id);
        String name = profile == null ? null : profile.withdrawn() ? "탈퇴한 사용자" : profile.name();
        return new NextSchedule(next.startsAt(), next.helpType(), name);
    }

    public int remainingScheduleCount() {
        return stats.acceptedCount() + stats.inProgressCount() - (stats.nextSchedule() == null ? 0 : 1);
    }

    public RequestCounts requestCounts() {
        return new RequestCounts(stats.acceptedCount(), stats.inProgressCount(), stats.waitingCount(),
                stats.noAssigneeCount(), responseNeeded);
    }

    public record NextSchedule(LocalDateTime startsAt, HelpType helpType, String assigneeName) {}

    public record RequestCounts(int accepted, int inProgress, int waiting, int noAssignee, long responseNeeded) {}
}
