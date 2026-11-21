package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.event.SituationEndedEvent;
import com.gyeot.domain.situation.repository.SituationEndRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.*;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SituationExpirationServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 0, 0);
    private SituationEndRepository repository;
    private ApplicationEventPublisher publisher;
    private SituationExpirationService service;

    @BeforeEach
    void setUp() {
        repository = mock(SituationEndRepository.class);
        publisher = mock(ApplicationEventPublisher.class);
        // UTC 날짜는 11일, 한국 날짜는 12일입니다.
        Clock clock = Clock.fixed(Instant.parse("2026-11-11T15:00:00Z"), ZoneOffset.UTC);
        service = new SituationExpirationService(repository, new SituationEndProcessor(clock, publisher), clock);
    }

    @Test
    void 후보_조회는_한국_기준_현재_시각으로_누적_만료를_조회한다() {
        when(repository.findExpiredIds(NOW, 0L, org.springframework.data.domain.PageRequest.of(0, 100))).thenReturn(List.of(1L, 2L));
        assertThat(service.findCandidates(0L)).containsExactly(1L, 2L);
        verify(repository).findExpiredIds(NOW, 0L, org.springframework.data.domain.PageRequest.of(0, 100));
    }

    @Test
    void 어제_종료된_ACTIVE는_실제_처리_시각으로_종료한다() {
        Situation situation = givenSituation(NOW.minusDays(1));
        assertThat(service.expire(1L)).isTrue();
        assertThat(situation.getEndedAt()).isEqualTo(NOW);
        verify(publisher).publishEvent(new SituationEndedEvent(1L, situation.getEndedAt()));
    }

    @Test
    void 며칠_전_만료도_다음_실행에서_종료한다() {
        givenSituation(NOW.minusDays(5));
        assertThat(service.expire(1L)).isTrue();
    }

    @Test
    void 종료_시각과_현재가_같으면_종료한다() {
        Situation situation = givenSituation(NOW);
        assertThat(service.expire(1L)).isTrue();
        assertThat(situation.getEndedAt()).isEqualTo(NOW);
        verify(publisher).publishEvent(new SituationEndedEvent(1L, NOW));
    }

    @Test
    void 미래_종료는_제외한다() {
        givenSituation(NOW.plusDays(1));
        assertThat(service.expire(1L)).isFalse();
        verifyNoInteractions(publisher);
    }

    @Test
    void 후보여도_잠금_조회에서_이미_종료되었으면_건너뛴다() {
        Situation situation = givenSituation(NOW.minusDays(1));
        situation.end(NOW.minusDays(1).withHour(12));
        assertThat(service.expire(1L)).isFalse();
        assertThat(situation.getEndedAt()).isEqualTo(NOW.minusDays(1).withHour(12));
        verifyNoInteractions(publisher);
    }

    @Test
    void 후보_조회_후_종료_시각이_연장되었으면_최신_시각으로_건너뛴다() {
        when(repository.findExpiredIds(NOW, 0L, org.springframework.data.domain.PageRequest.of(0, 100))).thenReturn(List.of(1L));
        assertThat(service.findCandidates(0L)).containsExactly(1L);
        givenSituation(NOW.plusDays(1));
        assertThat(service.expire(1L)).isFalse();
        verifyNoInteractions(publisher);
    }

    @Test
    void 후보가_삭제되었으면_건너뛴다() {
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        assertThat(service.expire(1L)).isFalse();
        verifyNoInteractions(publisher);
    }

    @Test
    void 반복_처리는_최초_시각과_이벤트_하나만_유지한다() {
        Situation situation = givenSituation(NOW.minusDays(1));
        assertThat(service.expire(1L)).isTrue();
        assertThat(service.expire(1L)).isFalse();
        assertThat(situation.getEndedAt()).isEqualTo(NOW);
        verify(publisher, times(1)).publishEvent(any(SituationEndedEvent.class));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1000, 0, 1000})
    void 자정이_아닌_서울_시각에서도_마이크로초_종료_경계를_지킨다(long offsetNanos) {
        LocalDateTime now = LocalDateTime.of(2026, 11, 12, 14, 30, 15, 123456000);
        Clock clock = Clock.fixed(now.atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneOffset.UTC);
        service = new SituationExpirationService(repository, new SituationEndProcessor(clock, publisher), clock);
        LocalDateTime endAt = now.plusNanos(offsetNanos);
        Situation situation = givenSituation(endAt);

        when(repository.findExpiredIds(now, 100L, org.springframework.data.domain.PageRequest.of(0, 100)))
                .thenReturn(List.of(1L));
        assertThat(service.findCandidates(100L)).containsExactly(1L);
        verify(repository).findExpiredIds(now, 100L, org.springframework.data.domain.PageRequest.of(0, 100));
        assertThat(service.expire(1L)).isEqualTo(offsetNanos <= 0);
        assertThat(situation.getEndAt()).isEqualTo(endAt);
        if (offsetNanos <= 0) {
            assertThat(situation.getEndedAt()).isEqualTo(now);
            verify(publisher).publishEvent(new SituationEndedEvent(1L, now));
        } else {
            assertThat(situation.getEndedAt()).isNull();
            verifyNoInteractions(publisher);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 수동과_자동_종료의_실행_순서가_달라도_상태_전이와_이벤트는_한번이다(boolean manualFirst) {
        Clock clock = Clock.fixed(Instant.parse("2026-11-11T15:00:00Z"), ZoneOffset.UTC);
        var manual = new SituationEndService(repository, new SituationEndProcessor(clock, publisher));
        Situation situation = givenSituation(NOW);
        if (manualFirst) {
            assertThat(manual.end(1L, 10L).endedAt()).isEqualTo(NOW);
            assertThat(service.expire(1L)).isFalse();
        } else {
            assertThat(service.expire(1L)).isTrue();
            assertThatThrownBy(() -> manual.end(1L, 10L))
                    .isInstanceOfSatisfying(com.gyeot.global.exception.BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(
                                    com.gyeot.domain.situation.exception.SituationErrorCode.SITUATION_ALREADY_ENDED));
        }
        assertThat(situation.getEndAt()).isEqualTo(NOW);
        assertThat(situation.getEndedAt()).isEqualTo(NOW);
        verify(publisher, times(1)).publishEvent(new SituationEndedEvent(1L, NOW));
    }

    private Situation givenSituation(LocalDateTime endAt) {
        Situation situation = Situation.create(10L, "돌봄", "병원 동행", NOW.minusDays(10), endAt);
        ReflectionTestUtils.setField(situation, "id", 1L);
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(situation));
        return situation;
    }
}
