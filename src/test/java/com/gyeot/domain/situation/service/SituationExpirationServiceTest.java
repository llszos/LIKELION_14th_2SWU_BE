package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.event.SituationEndedEvent;
import com.gyeot.domain.situation.repository.SituationEndRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.*;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SituationExpirationServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);
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
    void 후보_조회는_한국_기준_오늘로_누적_만료를_조회한다() {
        when(repository.findExpiredIds(TODAY, 0L, org.springframework.data.domain.PageRequest.of(0, 100))).thenReturn(List.of(1L, 2L));
        assertThat(service.findCandidates(0L)).containsExactly(1L, 2L);
        verify(repository).findExpiredIds(TODAY, 0L, org.springframework.data.domain.PageRequest.of(0, 100));
    }

    @Test
    void 어제_종료된_ACTIVE는_실제_처리_시각으로_종료한다() {
        Situation situation = givenSituation(TODAY.minusDays(1));
        assertThat(service.expire(1L)).isTrue();
        assertThat(situation.getEndedAt()).isEqualTo(TODAY.atStartOfDay());
        verify(publisher).publishEvent(new SituationEndedEvent(1L, situation.getEndedAt()));
    }

    @Test
    void 며칠_전_만료도_다음_실행에서_종료한다() {
        givenSituation(TODAY.minusDays(5));
        assertThat(service.expire(1L)).isTrue();
    }

    @Test
    void 오늘_종료는_제외한다() {
        givenSituation(TODAY);
        assertThat(service.expire(1L)).isFalse();
        verifyNoInteractions(publisher);
    }

    @Test
    void 미래_종료는_제외한다() {
        givenSituation(TODAY.plusDays(1));
        assertThat(service.expire(1L)).isFalse();
        verifyNoInteractions(publisher);
    }

    @Test
    void 후보여도_잠금_조회에서_이미_종료되었으면_건너뛴다() {
        Situation situation = givenSituation(TODAY.minusDays(1));
        situation.end(TODAY.minusDays(1).atTime(12, 0));
        assertThat(service.expire(1L)).isFalse();
        assertThat(situation.getEndedAt()).isEqualTo(TODAY.minusDays(1).atTime(12, 0));
        verifyNoInteractions(publisher);
    }

    @Test
    void 후보_조회_후_종료일이_연장되었으면_최신_날짜로_건너뛴다() {
        when(repository.findExpiredIds(TODAY, 0L, org.springframework.data.domain.PageRequest.of(0, 100))).thenReturn(List.of(1L));
        assertThat(service.findCandidates(0L)).containsExactly(1L);
        givenSituation(TODAY.plusDays(1));
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
        Situation situation = givenSituation(TODAY.minusDays(1));
        assertThat(service.expire(1L)).isTrue();
        assertThat(service.expire(1L)).isFalse();
        assertThat(situation.getEndedAt()).isEqualTo(TODAY.atStartOfDay());
        verify(publisher, times(1)).publishEvent(any(SituationEndedEvent.class));
    }

    private Situation givenSituation(LocalDate endDate) {
        Situation situation = Situation.create(10L, "돌봄", "병원 동행", TODAY.minusDays(10), endDate);
        ReflectionTestUtils.setField(situation, "id", 1L);
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(situation));
        return situation;
    }
}
