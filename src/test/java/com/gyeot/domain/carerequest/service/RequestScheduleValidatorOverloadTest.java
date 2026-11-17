package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.situation.query.SituationParticipantReader;
import com.gyeot.domain.situation.query.SituationParticipantReader.SituationSummary;
import com.gyeot.global.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static com.gyeot.domain.carerequest.exception.CareRequestErrorCode.REQUEST_SCHEDULE_INVALID;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 상황 기간 검증의 두 메서드가 같은 규칙으로 동작하는지 확인합니다. (#116 시간 모델, #137)
 * - requireWithinSituation(상황 ID, ...): 요청 수정(#73)이 씀. B에서 상황을 조회해 확인
 * - requireWithinSituation(상황 요약, ...): 요청 생성(#69)이 씀. 이미 조회한 상황으로 확인
 * 규칙: 상황 startAt ≤ 요청 startsAt < 요청 endsAt ≤ 상황 endAt
 */
class RequestScheduleValidatorOverloadTest {

    private static final Long SITUATION_ID = 1L;
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 20, 9, 0);  // 0시가 아닌 시각
    private static final LocalDateTime END = LocalDateTime.of(2026, 11, 22, 18, 0);
    private static final SituationSummary SITUATION = new SituationSummary(10L, START, END, "ACTIVE");

    private final SituationParticipantReader reader = mock(SituationParticipantReader.class);
    private final RequestScheduleValidator validator = new RequestScheduleValidator(reader);

    @BeforeEach
    void setUp() {
        when(reader.findSituation(SITUATION_ID)).thenReturn(Optional.of(SITUATION));
    }

    @Test
    void 요청_시작이_상황_시작과_같고_종료가_상황_종료와_같으면_두_메서드_모두_허용한다() {
        assertAllowed(START, END);
    }

    @Test
    void 요청_시작이_상황_시작보다_1분_이르면_두_메서드_모두_거부한다() {
        assertRejected(START.minusMinutes(1), START.plusHours(2));
    }

    @Test
    void 요청_종료가_상황_종료보다_1분_늦으면_날짜가_같아도_두_메서드_모두_거부한다() {
        assertRejected(END.minusHours(2), END.plusMinutes(1));
    }

    @Test
    void 요청_시작과_종료가_같거나_뒤집히면_두_메서드_모두_거부한다() {
        assertRejected(START.plusHours(1), START.plusHours(1));
        assertRejected(START.plusHours(2), START.plusHours(1));
    }

    // === 도우미 ===

    private void assertAllowed(LocalDateTime startsAt, LocalDateTime endsAt) {
        assertThatCode(() -> validator.requireWithinSituation(SITUATION_ID, startsAt, endsAt))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.requireWithinSituation(SITUATION, startsAt, endsAt))
                .doesNotThrowAnyException();
    }

    private void assertRejected(LocalDateTime startsAt, LocalDateTime endsAt) {
        assertThatThrownBy(() -> validator.requireWithinSituation(SITUATION_ID, startsAt, endsAt))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(REQUEST_SCHEDULE_INVALID);
        assertThatThrownBy(() -> validator.requireWithinSituation(SITUATION, startsAt, endsAt))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(REQUEST_SCHEDULE_INVALID);
    }
}