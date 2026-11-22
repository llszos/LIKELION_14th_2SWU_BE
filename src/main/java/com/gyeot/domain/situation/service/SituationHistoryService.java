package com.gyeot.domain.situation.service;

import com.gyeot.domain.carerequest.query.CareRequestSummaryReader;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.HelpRecord;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.HelpRecordFilter;
import com.gyeot.domain.carerequest.query.RequestDisplayStatus;
import com.gyeot.domain.user.query.UserProfileQuery;
import com.gyeot.domain.situation.dto.response.SituationHistoryDetailResponse;
import com.gyeot.domain.situation.dto.response.SituationHistoryListResponse;
import com.gyeot.domain.situation.dto.response.SituationHistoryPeopleResponse;
import com.gyeot.domain.situation.repository.SituationHistoryQueryRepository;
import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.gyeot.domain.situation.exception.SituationHistoryErrorCode.SITUATION_NOT_FOUND;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SituationHistoryService {
    private final SituationHistoryQueryRepository repository;
    private final ObjectProvider<CareRequestSummaryReader> requestReader;
    private final UserProfileQuery profiles;

    public enum Filter { ALL, HELPED, RECEIVED }

    public SituationHistoryListResponse list(Long userId, int page, int size, Filter filter, Long personId) {
        if (page < 0 || size < 1 || size > 100 || (personId != null && personId <= 0)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("endedAt"), Sort.Order.desc("id")));
        if (filter == Filter.ALL && personId == null) {
            return withRequestCounts(repository.findHistory(userId, pageable));
        }

        var situationIds = completedRecords(userId, filter)
                .filter(record -> personId == null || personId.equals(counterpartId(record)))
                .map(HelpRecord::situationId).collect(Collectors.toSet());
        if (situationIds.isEmpty()) {
            return new SituationHistoryListResponse(List.of(), page, size, false);
        }
        return withRequestCounts(repository.findFilteredHistory(userId, situationIds, pageable));
    }

    private SituationHistoryListResponse withRequestCounts(Slice<Situation> page) {
        if (page.isEmpty()) {
            return SituationHistoryListResponse.from(page, Map.of());
        }
        var ids = page.stream().map(Situation::getId).toList();
        var counts = Objects.requireNonNull(reader().getPastSituationStats(ids), "지난 상황 요청 집계가 필요합니다.");
        return SituationHistoryListResponse.from(page, counts);
    }

    public SituationHistoryPeopleResponse people(Long userId, Filter filter) {
        var records = completedRecords(userId, filter).toList();
        if (records.isEmpty()) {
            return new SituationHistoryPeopleResponse(List.of());
        }
        var situationIds = records.stream().map(HelpRecord::situationId).collect(Collectors.toSet());
        var accessible = new HashSet<>(repository.findAccessibleHistoryIds(userId, situationIds));
        var personIds = records.stream()
                .filter(record -> accessible.contains(record.situationId()))
                .map(SituationHistoryService::counterpartId).filter(Objects::nonNull)
                .filter(id -> !id.equals(userId)).collect(Collectors.toSet());
        if (personIds.isEmpty()) {
            return new SituationHistoryPeopleResponse(List.of());
        }
        var profilesById = profiles.findProfilesByUserIds(personIds);
        var items = personIds.stream().map(id -> {
            var profile = profilesById.get(id);
            String name = profile == null ? null : profile.withdrawn() ? "탈퇴한 사용자" : profile.name();
            return new SituationHistoryPeopleResponse.Item(id, name);
        }).sorted(Comparator.comparing(SituationHistoryPeopleResponse.Item::name,
                Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(SituationHistoryPeopleResponse.Item::userId)).toList();
        return new SituationHistoryPeopleResponse(items);
    }

    private Stream<HelpRecord> completedRecords(Long userId, Filter filter) {
        return reader().getHelpRecords(userId, HelpRecordFilter.ALL, null, Pageable.unpaged()).stream()
                .filter(record -> record.status() == RequestDisplayStatus.COMPLETED && record.situationId() != null)
                .filter(record -> switch (filter) {
                    case ALL -> true;
                    case HELPED -> record.role() == HelpRecordFilter.GIVEN;
                    case RECEIVED -> record.role() == HelpRecordFilter.RECEIVED;
                });
    }

    private static Long counterpartId(HelpRecord record) {
        return record.role() == HelpRecordFilter.GIVEN ? record.requesterId() : record.assigneeId();
    }

    /** 기존 ENDED·접근 조건을 검증한 뒤 실제 조회자 ID로 C의 기록을 조회합니다. */
    public SituationHistoryDetailResponse detail(Long situationId, Long userId) {
        var situation = repository.findAccessibleHistory(situationId, userId)
                .orElseThrow(() -> new BusinessException(SITUATION_NOT_FOUND));
        var requests = Objects.requireNonNull(reader().getPastRequestRecords(situationId, userId),
                "지난 요청 기록 조회 결과는 null일 수 없습니다.");
        var assigneeIds = requests.stream().map(CareRequestSummaryReader.PastRequestRecord::assigneeId)
                .filter(Objects::nonNull).distinct().toList();
        return SituationHistoryDetailResponse.from(situation, userId, requests,
                profiles.findProfilesByUserIds(assigneeIds));
    }

    private CareRequestSummaryReader reader() {
        var reader = requestReader.getIfAvailable();
        if (reader == null) {
            throw new IllegalStateException("지난 요청 기록 조회 구현이 준비되지 않았습니다.");
        }
        return reader;
    }
}
