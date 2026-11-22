package com.gyeot.domain.home.service;

import com.gyeot.domain.access.query.SharingCountReader;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader;
import com.gyeot.domain.home.dto.response.HomeResponse;
import com.gyeot.domain.situation.repository.SituationRepository;
import com.gyeot.domain.situation.repository.SituationRepository.HomeCardRow;
import com.gyeot.domain.situation.repository.SituationParticipantRepository;
import com.gyeot.domain.user.query.UserProfileQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.gyeot.domain.home.dto.response.HomeResponse.counterpartId;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HomeService {
    private final CareRequestSummaryReader requestReader;
    private final SharingCountReader sharingCounts;
    private final UserProfileQuery userProfiles;
    private final SituationRepository situations;
    private final SituationParticipantRepository participants;
    private final Clock clock;

    public HomeResponse getHome(Long userId) {
        Objects.requireNonNull(userId, "로그인 사용자 ID는 필수입니다.");
        LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        var overview = Objects.requireNonNull(requestReader.getHomeOverview(userId, now),
                "C 홈 조회 결과는 null일 수 없습니다.");
        long activeSharings = sharingCounts.countActiveSharings(userId);
        var upcoming = overview.upcoming().stream().limit(2).toList();
        var rows = situations.findHomeCards(userId);
        var ownedIds = rows.stream().filter(row -> row.getCreatorId().equals(userId))
                .map(HomeCardRow::getSituationId).toList();
        var ownedStats = ownedIds.isEmpty()
                ? Map.<Long, CareRequestSummaryReader.SituationRequestStats>of()
                : requestReader.getSituationStats(ownedIds, now);
        var neededBySituation = overview.responseNeeded().stream()
                .filter(item -> item.situationId() != null)
                .collect(Collectors.groupingBy(item -> item.situationId(), Collectors.counting()));
        var cards = rows.stream().map(row -> {
            long responseNeeded = neededBySituation.getOrDefault(row.getSituationId(), 0L);
            if (row.getCreatorId().equals(userId)) {
                return new CardData(row, true, ownedStats.getOrDefault(row.getSituationId(),
                        CareRequestSummaryReader.SituationRequestStats.empty()), responseNeeded);
            }
            return new CardData(row, false, participatingStats(row.getSituationId(), userId, now), responseNeeded);
        }).toList();
        var profileIds = Stream.of(
                        overview.responseNeeded().stream().map(item -> item.requesterId()),
                        overview.sentRequests().stream().map(HomeResponse::displayTargetId),
                        upcoming.stream().map(item -> counterpartId(item, userId)),
                        cards.stream().map(card -> card.stats().nextSchedule()).filter(Objects::nonNull)
                                .map(CareRequestSummaryReader.UpcomingSchedule::assigneeId))
                .flatMap(ids -> ids)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        var profiles = profileIds.isEmpty() ? Map.<Long, UserProfileQuery.UserProfile>of()
                : userProfiles.findProfilesByUserIds(profileIds);
        var my = new ArrayList<HomeResponse.SituationCard>();
        var participating = new ArrayList<HomeResponse.SituationCard>();
        for (var card : cards) {
            (card.owner() ? my : participating).add(card.toResponse(profiles));
        }
        boolean isNewUser = rows.isEmpty() && !situations.existsByCreatorId(userId)
                && !participants.existsByUserId(userId);
        return HomeResponse.from(overview, activeSharings, upcoming, userId, profiles,
                new HomeResponse.SituationCards(List.copyOf(my), List.copyOf(participating)), isNewUser);
    }

    private CareRequestSummaryReader.SituationRequestStats participatingStats(
            Long situationId, Long userId, LocalDateTime now) {
        var requests = requestReader.getSituationRequests(situationId, userId, now);
        CareRequestSummaryReader.UpcomingSchedule next = null;
        int accepted = 0;
        int inProgress = 0;
        for (var request : requests) {
            if (!userId.equals(request.targetUserId())) {
                continue;
            }
            switch (request.displayStatus()) {
                case ACCEPTED -> accepted++;
                case IN_PROGRESS -> inProgress++;
                default -> { continue; }
            }
            // C Reader가 시작 시각 순서로 정렬한, 조회자가 담당하는 일정입니다.
            if (next == null) {
                next = new CareRequestSummaryReader.UpcomingSchedule(request.requestId(), request.helpType(),
                        request.startsAt(), null, request.requesterId(), request.targetUserId());
            }
        }
        return new CareRequestSummaryReader.SituationRequestStats(accepted, inProgress, 0, 0, next);
    }

    private record CardData(HomeCardRow row, boolean owner, CareRequestSummaryReader.SituationRequestStats stats,
                            long responseNeeded) {
        HomeResponse.SituationCard toResponse(Map<Long, UserProfileQuery.UserProfile> profiles) {
            var next = stats.nextSchedule();
            var nextSchedule = next == null ? null : new HomeResponse.NextSchedule(
                    next.startsAt(), next.helpType(), HomeResponse.name(profiles, next.assigneeId()));
            var counts = new HomeResponse.RequestCounts(stats.acceptedCount(), stats.inProgressCount(),
                    stats.waitingCount(), stats.noAssigneeCount(), responseNeeded);
            int remaining = stats.acceptedCount() + stats.inProgressCount() - (next == null ? 0 : 1);
            return new HomeResponse.SituationCard(row.getSituationId(), row.getName(), row.getStartAt(), row.getEndAt(),
                    row.getParticipantCount(), owner, nextSchedule, remaining, counts);
        }
    }
}
