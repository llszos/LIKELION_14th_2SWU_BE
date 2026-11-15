package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.entity.*;
import com.gyeot.domain.situation.event.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 메모리 상태·이벤트 호출만 검증하며 실제 DB 롤백을 검증하지 않습니다. */
class SituationParticipantRemovalProcessorTest {
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-15T01:00:00.123456789Z"), ZoneOffset.UTC);
    private final LocalDateTime occurredAt = LocalDateTime.of(2026, 11, 15, 10, 0, 0, 123456000);
    private final SituationParticipantRemovalProcessor processor = new SituationParticipantRemovalProcessor(publisher, clock);

    @Test
    void removesAndPublishesTargetUserIdAndRecordedSeoulTimeOnlyOnce() {
        var target = participant(SituationParticipantRole.PARTICIPANT);
        processor.removeValidated(3L, target);
        processor.removeValidated(3L, target);
        assertThat(target.getStatus()).isEqualTo(SituationParticipantStatus.REMOVED);
        assertThat(target.getLeftAt()).isEqualTo(occurredAt);
        verify(publisher).publishEvent(event());
        verifyNoMoreInteractions(publisher);
    }

    @Test
    void ownerIsNotChangedAndDoesNotPublish() {
        var target = participant(SituationParticipantRole.OWNER);
        assertThatThrownBy(() -> processor.removeValidated(3L, target)).isInstanceOf(IllegalStateException.class);
        assertActive(target);
        verifyNoInteractions(publisher);
    }

    @Test
    void leftAndRemovedHistoryKeepTheirOriginalStateAndTime() {
        for (var status : new SituationParticipantStatus[]{SituationParticipantStatus.LEFT, SituationParticipantStatus.REMOVED}) {
            var target = participant(SituationParticipantRole.PARTICIPANT);
            var original = occurredAt.minusHours(1);
            if (status == SituationParticipantStatus.LEFT) target.leave(original); else target.remove(original);
            processor.removeValidated(3L, target);
            assertThat(target.getStatus()).isEqualTo(status);
            assertThat(target.getLeftAt()).isEqualTo(original);
        }
        verifyNoInteractions(publisher);
    }

    @Test
    void differentSituationIsRejectedBeforeMutationOrEvent() {
        var target = participant(SituationParticipantRole.PARTICIPANT);
        assertThatThrownBy(() -> processor.removeValidated(99L, target)).isInstanceOf(IllegalArgumentException.class);
        assertActive(target);
        verifyNoInteractions(publisher);
    }

    @Test
    void clockFailureStopsBeforeMutationAndEvent() {
        var target = participant(SituationParticipantRole.PARTICIPANT);
        Clock failingClock = mock(Clock.class);
        var failure = new DateTimeException("clock failed");
        when(failingClock.withZone(ZoneId.of("Asia/Seoul"))).thenThrow(failure);
        var subject = new SituationParticipantRemovalProcessor(publisher, failingClock);
        assertThatThrownBy(() -> subject.removeValidated(3L, target)).isSameAs(failure);
        assertActive(target);
        verifyNoInteractions(publisher);
    }

    @Test
    void publicationFailurePropagatesWithoutClaimingMemoryRollback() {
        var target = participant(SituationParticipantRole.PARTICIPANT);
        var failure = new IllegalStateException("listener failed");
        doThrow(failure).when(publisher).publishEvent(event());
        assertThatThrownBy(() -> processor.removeValidated(3L, target)).isSameAs(failure);
        // 직접 호출에는 트랜잭션 프록시가 없으며 메모리 객체는 이미 변경됐습니다.
        assertThat(target.getStatus()).isEqualTo(SituationParticipantStatus.REMOVED);
        assertThat(target.getLeftAt()).isEqualTo(occurredAt);
        verify(publisher).publishEvent(event());
        verifyNoMoreInteractions(publisher);
    }

    private SituationParticipant participant(SituationParticipantRole role) {
        var situation = Situation.create(10L, "유형", "이름", LocalDate.of(2026, 11, 15), LocalDate.of(2026, 11, 20));
        ReflectionTestUtils.setField(situation, "id", 3L);
        var participant = SituationParticipant.create(situation, role == SituationParticipantRole.OWNER ? 10L : 20L,
                role, occurredAt.minusDays(1));
        ReflectionTestUtils.setField(participant, "id", 7L);
        return participant;
    }

    private SituationParticipantRemovedEvent event() {
        return new SituationParticipantRemovedEvent(3L, 20L, SituationParticipantRemovalReason.PARTICIPANT_LEFT, occurredAt);
    }

    private void assertActive(SituationParticipant target) {
        assertThat(target.getStatus()).isEqualTo(SituationParticipantStatus.ACTIVE);
        assertThat(target.getLeftAt()).isNull();
    }
}
