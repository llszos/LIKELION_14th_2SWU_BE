package com.gyeot.domain.situation.dto.response;

import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.entity.SituationStatus;
import com.gyeot.domain.situation.service.SituationCardActivity;
import com.gyeot.domain.situation.service.SituationCardActivity.NextSchedule;
import com.gyeot.domain.situation.service.SituationCardActivity.RequestCounts;
import com.gyeot.domain.user.query.UserProfileQuery.UserProfile;
import org.springframework.data.domain.Slice;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 진행 중 상황과 조회자별 카드 통계를 반환합니다. */
public record SituationListResponse(List<Item> items, int page, int size, boolean hasNext) {
    public static SituationListResponse from(Slice<Item> slice) {
        return new SituationListResponse(slice.getContent(),
                slice.getNumber(), slice.getSize(), slice.hasNext());
    }

    public record Item(Long situationId, String name, String situationType,
                       LocalDateTime startAt, LocalDateTime endAt, SituationStatus status,
                       long participantCount, RequestCounts requestCounts, NextSchedule nextSchedule,
                       int remainingScheduleCount) {
        public static Item from(Situation situation, long participantCount, SituationCardActivity activity,
                                Map<Long, UserProfile> profiles) {
            return new Item(situation.getId(), situation.getTitle(), situation.getSituationType(),
                    situation.getStartAt(), situation.getEndAt(), situation.getStatus(), participantCount, activity.requestCounts(),
                    activity.nextSchedule(profiles), activity.remainingScheduleCount());
        }
    }
}
