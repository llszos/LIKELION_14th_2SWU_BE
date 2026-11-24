package com.gyeot.domain.situation.dto.response;

import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.PastSituationStats;
import com.gyeot.domain.situation.entity.Situation;
import org.springframework.data.domain.Slice;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 서울 현지 시각의 [startAt, endAt)을 손실 없이 반환하며 endedAt은 실제 종료 시각입니다. */
public record SituationHistoryListResponse(List<Item> items, int page, int size, boolean hasNext) {
    public static SituationHistoryListResponse from(Slice<Situation> slice, Map<Long, PastSituationStats> stats) {
        return new SituationHistoryListResponse(slice.getContent().stream()
                .map(situation -> Item.from(situation, stats.getOrDefault(situation.getId(), PastSituationStats.empty())
                        .requestCount())).toList(),
                slice.getNumber(), slice.getSize(), slice.hasNext());
    }

    public record Item(Long situationId, String name, String situationType,
                       LocalDateTime startAt, LocalDateTime endAt, LocalDateTime endedAt, int requestCount) {
        static Item from(Situation situation, int requestCount) {
            return new Item(situation.getId(), situation.getTitle(), situation.getSituationType(),
                    situation.getStartAt(), situation.getEndAt(), situation.getEndedAt(), requestCount);
        }
    }
}
