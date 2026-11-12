package com.gyeot.domain.situation.dto.response;

import com.gyeot.domain.situation.entity.Situation;
import org.springframework.data.domain.Slice;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record SituationHistoryListResponse(List<Item> items, int page, int size, boolean hasNext) {
    public static SituationHistoryListResponse from(Slice<Situation> slice) {
        return new SituationHistoryListResponse(slice.getContent().stream().map(Item::from).toList(),
                slice.getNumber(), slice.getSize(), slice.hasNext());
    }

    public record Item(Long situationId, String name, String situationType,
                       LocalDate startDate, LocalDate endDate, LocalDateTime endedAt) {
        static Item from(Situation situation) {
            return new Item(situation.getId(), situation.getTitle(), situation.getSituationType(),
                    situation.getStartDate(), situation.getEndDate(), situation.getEndedAt());
        }
    }
}
