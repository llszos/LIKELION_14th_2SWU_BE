package com.gyeot.domain.situation.dto.response;

import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.entity.SituationStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** HTTP 공개 전 내부 매핑만 검증합니다. API 필드 계약 확정을 의미하지 않습니다. */
class SituationHistoryDetailResponseTest {
    @Test
    void 요청이_없어도_상황의_전체_시각_정보를_반환한다() {
        LocalDateTime start = LocalDateTime.of(2026, 12, 31, 23, 59, 59, 123456000);
        LocalDateTime end = LocalDateTime.of(2027, 1, 1, 0, 0);
        LocalDateTime actual = start.plusNanos(1000);
        Situation situation = Situation.create(10L, "돌봄", "연말", start, end);
        situation.end(actual);

        var result = SituationHistoryDetailResponse.from(situation, List.of());

        assertThat(result.situation().name()).isEqualTo("연말");
        assertThat(result.situation().status()).isEqualTo(SituationStatus.ENDED);
        assertThat(result.situation().startAt()).isEqualTo(start);
        assertThat(result.situation().endAt()).isEqualTo(end);
        assertThat(result.situation().endedAt()).isEqualTo(actual);
        assertThat(result.requests()).isEmpty();
    }

    @Test
    void 배타적_종료_경계_전후를_날짜로_축소하거나_하루_늘리지_않는다() {
        LocalDateTime midnight = LocalDateTime.of(2027, 1, 1, 0, 0);
        for (long offset : new long[]{-1000, 0, 1000}) {
            LocalDateTime end = midnight.plusNanos(offset);
            Situation situation = Situation.create(10L, "돌봄", "경계", midnight.minusHours(1), end);
            situation.end(end.plusMinutes(1));
            var info = SituationHistoryDetailResponse.from(situation, List.of()).situation();
            assertThat(info.startAt()).isEqualTo(midnight.minusHours(1));
            assertThat(info.endAt()).isEqualTo(end);
            assertThat(info.endedAt()).isEqualTo(end.plusMinutes(1));
        }
    }
}
