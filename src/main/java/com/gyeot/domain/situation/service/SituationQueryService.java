package com.gyeot.domain.situation.service;

import com.gyeot.domain.carerequest.query.CareRequestSummaryReader;
import com.gyeot.domain.user.query.UserProfileQuery;
import com.gyeot.domain.situation.dto.response.SituationListResponse;
import com.gyeot.domain.situation.dto.response.SituationDetailResponse;
import com.gyeot.domain.situation.entity.SituationParticipantStatus;
import com.gyeot.domain.situation.entity.SituationStatus;
import com.gyeot.domain.situation.exception.SituationQueryErrorCode;
import com.gyeot.domain.situation.repository.SituationParticipantRepository;
import com.gyeot.domain.situation.repository.SituationParticipantRepository.ActiveCount;
import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.repository.SituationRepository;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SituationQueryService {
    private final SituationRepository repository;
    private final SituationParticipantRepository participantRepository;
    private final ObjectProvider<CareRequestSummaryReader> requestReaders;
    private final Clock clock;
    private final UserProfileQuery profiles;

    public SituationDetailResponse detailActive(Long situationId, Long userId) {
        var situation = repository.findById(situationId)
                .orElseThrow(() -> new BusinessException(SituationQueryErrorCode.SITUATION_NOT_FOUND));
        if (situation.getStatus() != SituationStatus.ACTIVE
                || !participantRepository.existsBySituation_IdAndUserIdAndStatus(
                        situationId, userId, SituationParticipantStatus.ACTIVE)) {
            throw new BusinessException(SituationQueryErrorCode.SITUATION_NOT_FOUND);
        }
        // B 접근 검증을 먼저 마친 뒤, 실제 조회자 ID를 C에 전달합니다.
        CareRequestSummaryReader reader = requestReader();
        LocalDateTime now = LocalDateTime.now(clock);
        var stats = Objects.requireNonNull(reader.getSituationStats(List.of(situationId), now),
                "상황 요청 통계는 null일 수 없습니다.");
        var counts = Objects.requireNonNull(stats.get(situationId), "상황 요청 통계가 누락됐습니다.");
        int activeRequestCount = counts.waitingCount() + counts.noAssigneeCount()
                + counts.acceptedCount() + counts.inProgressCount();
        var requests = Objects.requireNonNull(reader.getSituationRequests(situationId, userId, now),
                "상황 요청 목록은 null일 수 없습니다.");
        var userIds = requests.stream().map(CareRequestSummaryReader.RequestSummary::targetUserId)
                .filter(Objects::nonNull).distinct().toList();
        return SituationDetailResponse.from(situation, userId, activeRequestCount, requests,
                profiles.findProfilesByUserIds(userIds));
    }

    public SituationListResponse listActive(Long userId, int page, int size) {
        if (page < 0 || size < 1) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        var pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        var pageOfSituations = repository.findActiveForParticipant(userId, pageable);
        if (pageOfSituations.isEmpty()) {
            return new SituationListResponse(List.of(), pageOfSituations.getNumber(),
                    pageOfSituations.getSize(), pageOfSituations.hasNext());
        }

        var creators = pageOfSituations.stream().collect(Collectors.toMap(Situation::getId, Situation::getCreatorId));
        var participantCounts = participantRepository.countActiveBySituationIds(creators.keySet()).stream()
                .collect(Collectors.toMap(ActiveCount::getSituationId, ActiveCount::getParticipantCount));
        LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        var reader = requestReader();
        var activity = SituationCardActivity.load(creators, userId, now, reader,
                reader.getHomeOverview(userId, now).responseNeeded());
        var assigneeIds = activity.values().stream().map(SituationCardActivity::nextAssigneeId)
                .filter(Objects::nonNull).distinct().toList();
        var names = assigneeIds.isEmpty() ? Map.<Long, UserProfileQuery.UserProfile>of()
                : profiles.findProfilesByUserIds(assigneeIds);

        return SituationListResponse.from(pageOfSituations.map(situation ->
                SituationListResponse.Item.from(situation, participantCounts.getOrDefault(situation.getId(), 0L),
                        activity.get(situation.getId()), names)));
    }

    private CareRequestSummaryReader requestReader() {
        var reader = requestReaders.getIfAvailable();
        if (reader == null) {
            throw new IllegalStateException("상황 요청 요약 조회 구현이 준비되지 않았습니다.");
        }
        return reader;
    }
}
