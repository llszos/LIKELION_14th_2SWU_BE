package com.gyeot.domain.home.service;

import com.gyeot.domain.access.query.SharingCountReader;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader;
import com.gyeot.domain.home.dto.response.HomeResponse;
import com.gyeot.domain.situation.repository.SituationRepository;
import com.gyeot.domain.situation.service.SituationCardActivity;
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
        var creatorsBySituation = rows.stream().collect(Collectors.toMap(
                HomeCardRow::getSituationId, HomeCardRow::getCreatorId));
        var activityBySituation = SituationCardActivity.load(creatorsBySituation, userId, now,
                requestReader, overview.responseNeeded());
        var profileIds = Stream.of(
                        overview.responseNeeded().stream().map(item -> item.requesterId()),
                        overview.sentRequests().stream().map(HomeResponse::displayTargetId),
                        upcoming.stream().map(item -> counterpartId(item, userId)),
                        activityBySituation.values().stream().map(SituationCardActivity::nextAssigneeId))
                .flatMap(ids -> ids)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        var profiles = profileIds.isEmpty() ? Map.<Long, UserProfileQuery.UserProfile>of()
                : userProfiles.findProfilesByUserIds(profileIds);
        var my = new ArrayList<HomeResponse.SituationCard>();
        var participating = new ArrayList<HomeResponse.SituationCard>();
        for (var row : rows) {
            boolean owner = userId.equals(row.getCreatorId());
            var activity = activityBySituation.get(row.getSituationId());
            var card = new HomeResponse.SituationCard(row.getSituationId(), row.getName(), row.getStartAt(),
                    row.getEndAt(), row.getParticipantCount(), owner, activity.nextSchedule(profiles),
                    activity.remainingScheduleCount(), activity.requestCounts());
            (owner ? my : participating).add(card);
        }
        boolean isNewUser = rows.isEmpty() && !situations.existsByCreatorId(userId)
                && !participants.existsByUserId(userId);
        return HomeResponse.from(overview, activeSharings, upcoming, userId, profiles,
                new HomeResponse.SituationCards(List.copyOf(my), List.copyOf(participating)), isNewUser);
    }
}
