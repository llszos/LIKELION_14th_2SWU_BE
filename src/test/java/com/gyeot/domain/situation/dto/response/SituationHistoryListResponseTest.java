package com.gyeot.domain.situation.dto.response;

import com.gyeot.domain.situation.entity.Situation;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.SliceImpl;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SituationHistoryListResponseTest {
    @Test
    void 서울_시각과_마이크로초를_손실없이_반환하고_실제_종료와_구분한다() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 1, 9, 15, 30, 123456000);
        LocalDateTime end = LocalDateTime.of(2026, 10, 31, 18, 45, 30, 654321000);
        Situation situation = Situation.create(10L, "돌봄", "지난 돌봄", start, end);
        LocalDateTime actualEnd = start.plusHours(2);
        situation.end(actualEnd);
        var item = SituationHistoryListResponse.from(new SliceImpl<>(List.of(situation))).items().getFirst();
        assertThat(item.startAt()).isEqualTo(start);
        assertThat(item.endAt()).isEqualTo(end);
        assertThat(item.endedAt()).isEqualTo(actualEnd);
    }

    @Test
    void 배타적_자정_종료와_직후_시각을_날짜로_축소하거나_늘리지_않는다() {
        LocalDateTime midnight = LocalDateTime.of(2027, 1, 1, 0, 0);
        for (long offset : new long[]{-1000, 0, 1000}) {
            LocalDateTime end = midnight.plusNanos(offset);
            Situation situation = Situation.create(10L, "돌봄", "연말", midnight.minusHours(1), end);
            situation.end(end.plusMinutes(1));
            var item = SituationHistoryListResponse.Item.from(situation);
            assertThat(item.startAt()).isEqualTo(midnight.minusHours(1));
            assertThat(item.endAt()).isEqualTo(end);
            assertThat(item.endedAt()).isEqualTo(end.plusMinutes(1));
        }
    }
}
