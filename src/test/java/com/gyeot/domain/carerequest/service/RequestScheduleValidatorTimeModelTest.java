package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.situation.query.SituationParticipantReader;
import com.gyeot.domain.situation.query.SituationParticipantReader.SituationSummary;
import com.gyeot.global.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** #116 임시 통합 후보: 날짜 축약 없이 상황의 실제 날짜시각 경계를 검증합니다. */
class RequestScheduleValidatorTimeModelTest {
    private final SituationParticipantReader reader = mock(SituationParticipantReader.class);
    private final RequestScheduleValidator validator = new RequestScheduleValidator(reader);
    private final LocalDateTime start = LocalDateTime.of(2026, 11, 20, 9, 30, 0, 123456000);
    private final LocalDateTime end = start.plusHours(8);

    private void situation() {
        when(reader.findSituation(1L)).thenReturn(Optional.of(new SituationSummary(10L, start, end, "ACTIVE")));
    }

    @Test void exactStartAndEndAreAllowed() {
        situation();
        assertThatCode(() -> validator.requireWithinSituation(1L, start, end)).doesNotThrowAnyException();
    }

    @Test void oneMicrosecondBeforeStartIsRejected() {
        situation();
        assertThatThrownBy(() -> validator.requireWithinSituation(1L, start.minusNanos(1000), end))
                .isInstanceOf(BusinessException.class);
    }

    @Test void oneMicrosecondAfterEndIsRejectedWithoutNextDayExpansion() {
        situation();
        assertThatThrownBy(() -> validator.requireWithinSituation(1L, start, end.plusNanos(1000)))
                .isInstanceOf(BusinessException.class);
    }

    @Test void equalOrReversedRequestTimesAreRejected() {
        assertThatThrownBy(() -> validator.requireWithinSituation(1L, start, start)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validator.requireWithinSituation(1L, end, start)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(reader);
    }

    @Test void missingSituationIsRejected() {
        assertThatThrownBy(() -> validator.requireWithinSituation(1L, start, end)).isInstanceOf(BusinessException.class);
    }

    @Test void midnightAndYearBoundaryRetainTime() {
        LocalDateTime first = LocalDateTime.of(2026, 12, 31, 23, 30);
        LocalDateTime last = first.plusHours(2);
        when(reader.findSituation(1L)).thenReturn(Optional.of(new SituationSummary(10L, first, last, "ACTIVE")));
        assertThatCode(() -> validator.requireWithinSituation(1L, first, last)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.requireWithinSituation(1L, first, last.plusMinutes(1))).isInstanceOf(BusinessException.class);
    }
}
