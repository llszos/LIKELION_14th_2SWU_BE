package com.gyeot.domain.situation.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

@SuppressWarnings("deprecation")
class SituationLegacyDateCompatibilityTest {
    @Test
    void 포함_종료일을_다음날_배타적_자정으로_변환하고_기존_날짜를_보존한다() {
        LocalDate start = LocalDate.of(2026, 11, 1);
        LocalDate end = LocalDate.of(2026, 11, 5);
        Situation situation = Situation.create(10L, "돌봄", "동행", start, end);
        assertThat(situation.getStartAt()).isEqualTo(start.atStartOfDay());
        assertThat(situation.getEndAt()).isEqualTo(end.plusDays(1).atStartOfDay());
        assertThat(situation.getStartDate()).isEqualTo(start);
        assertThat(situation.getEndDate()).isEqualTo(end);
    }

    @Test
    void 같은날_날짜_기간은_하루이고_시각_기간의_동일_경계는_여전히_거부한다() {
        LocalDate day = LocalDate.of(2026, 11, 12);
        Situation situation = Situation.create(10L, "돌봄", "동행", day, day);
        assertThat(situation.getStartAt()).isEqualTo(day.atStartOfDay());
        assertThat(situation.getEndAt()).isEqualTo(day.plusDays(1).atStartOfDay());
        assertThat(situation.getEndDate()).isEqualTo(day);
        assertThatThrownBy(() -> Situation.create(10L, "돌봄", "동행", day.atStartOfDay(), day.atStartOfDay()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 자정과_마이크로초_경계에서_마지막_포함_날짜를_반환한다() {
        LocalDateTime midnight = LocalDateTime.of(2027, 1, 1, 0, 0);
        for (long offset : new long[]{-1000, 0, 1000}) {
            LocalDateTime end = midnight.plusNanos(offset);
            Situation situation = Situation.create(10L, "돌봄", "동행", midnight.minusHours(1), end);
            assertThat(situation.getEndDate()).isEqualTo(
                    offset <= 0 ? LocalDate.of(2026, 12, 31) : LocalDate.of(2027, 1, 1));
            assertThat(situation.getStartAt()).isEqualTo(midnight.minusHours(1));
            assertThat(situation.getEndAt()).isEqualTo(end);
        }
    }

    @Test
    void 서울_오후_시각과_예정_종료는_그대로_두고_요약_날짜만_반환한다() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 12, 9, 30);
        LocalDateTime end = start.plusHours(6);
        Situation situation = Situation.create(10L, "돌봄", "동행", start, end);
        situation.end(start.plusHours(1));
        assertThat(situation.getStartDate()).isEqualTo(start.toLocalDate());
        assertThat(situation.getEndDate()).isEqualTo(end.toLocalDate());
        assertThat(situation.getStartAt()).isEqualTo(start);
        assertThat(situation.getEndAt()).isEqualTo(end);
        assertThat(situation.getEndedAt()).isEqualTo(start.plusHours(1));
    }

    @Test
    void 날짜_어댑터도_null_역전_오버플로와_필수값을_거부한다() {
        LocalDate day = LocalDate.of(2026, 11, 12);
        assertThatThrownBy(() -> Situation.create(10L, "돌봄", "동행", (LocalDate) null, day))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Situation.create(10L, "돌봄", "동행", day, (LocalDate) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Situation.create(10L, "돌봄", "동행", day.plusDays(1), day))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Situation.create(10L, "돌봄", "동행", day, LocalDate.MAX))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Situation.create(null, "돌봄", "동행", day, day))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Situation.create(10L, " ", "동행", day, day))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
