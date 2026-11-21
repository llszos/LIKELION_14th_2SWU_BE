package com.gyeot.domain.situation.dto.response;

import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.entity.SituationStatus;
import org.springframework.data.domain.Slice;

import java.time.LocalDateTime;
import java.util.List;

/** B 기본 목록 응답입니다. 미연동 통계·프로필·제안 필드는 포함하지 않습니다. */
public record SituationListResponse(List<Item> items, int page, int size, boolean hasNext) {
    public static SituationListResponse from(Slice<Situation> slice) {
        return new SituationListResponse(slice.getContent().stream().map(Item::from).toList(),
                slice.getNumber(), slice.getSize(), slice.hasNext());
    }

    public record Item(Long situationId, String name, String situationType,
                       LocalDateTime startAt, LocalDateTime endAt, SituationStatus status) {
        private static Item from(Situation situation) {
            return new Item(situation.getId(), situation.getTitle(), situation.getSituationType(),
                    situation.getStartAt(), situation.getEndAt(), situation.getStatus());
        }
    }
}
