package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.entity.*;
import com.gyeot.domain.situation.event.SituationParticipantRemovedEvent;
import com.gyeot.domain.situation.event.SituationParticipantRemovalReason;
import com.gyeot.domain.situation.exception.SituationLeaveErrorCode;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import com.gyeot.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SituationLeaveProcessorTest {
    private final SituationLeaveStore store = mock(SituationLeaveStore.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-13T01:00:00.123456789Z"), ZoneId.of("Asia/Seoul"));
    private final LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    private final SituationLeaveProcessor processor = new SituationLeaveProcessor(store, publisher, clock);
    private Situation situation;
    private SituationParticipant participant;

    @BeforeEach
    void setUp() {
        situation = Situation.create(1L, "돌봄", "병원 동행", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));
        participant = participant(10L, now.minusDays(1));
        when(store.findSituationForUpdate(3L)).thenReturn(Optional.of(situation));
        when(store.findParticipantsForUpdate(3L, 2L)).thenReturn(List.of(participant));
    }

    @Test
    void activeParticipantLeavesAndPublishesExactlyOneEventWithRecordedTime() {
        var response = processor.leave(3L, 2L);
        assertThat(participant.getStatus()).isEqualTo(SituationParticipantStatus.LEFT);
        assertThat(participant.getLeftAt()).isEqualTo(now);
        assertThat(response.situationId()).isEqualTo(3L);
        assertThat(response.userId()).isEqualTo(2L);
        assertThat(response.status()).isEqualTo(SituationParticipantStatus.LEFT);
        assertThat(response.leftAt()).isEqualTo(now);
        verify(publisher).publishEvent(new SituationParticipantRemovedEvent(3L, 2L,
                SituationParticipantRemovalReason.PARTICIPANT_LEFT, now));
        verifyNoMoreInteractions(publisher);
        var order = inOrder(store);
        order.verify(store).findSituationForUpdate(3L);
        order.verify(store).findParticipantsForUpdate(3L, 2L);
    }

    @Test
    void ownerIsForbiddenWithoutMutationOrEvent() {
        var owner = SituationParticipant.create(situation, 1L, SituationParticipantRole.OWNER, now.minusDays(1));
        when(store.findParticipantsForUpdate(3L, 1L)).thenReturn(List.of(owner));
        expectError(1L, CommonErrorCode.FORBIDDEN);
        assertThat(owner.getStatus()).isEqualTo(SituationParticipantStatus.ACTIVE);
        assertThat(owner.getLeftAt()).isNull();
    }

    @Test
    void endedSituationIsConflict() {
        situation.end(now.minusHours(1));
        expectError(2L, SituationLeaveErrorCode.SITUATION_ENDED);
        assertThat(participant.getStatus()).isEqualTo(SituationParticipantStatus.ACTIVE);
        verify(store, never()).findParticipantsForUpdate(anyLong(), anyLong());
    }

    @Test
    void missingSituationIsNotFound() {
        when(store.findSituationForUpdate(3L)).thenReturn(Optional.empty());
        expectError(2L, CommonErrorCode.NOT_FOUND);
        verify(store, never()).findParticipantsForUpdate(anyLong(), anyLong());
    }

    @Test
    void missingParticipationIsNotFound() {
        when(store.findParticipantsForUpdate(3L, 2L)).thenReturn(List.of());
        expectError(2L, CommonErrorCode.NOT_FOUND);
    }

    @Test
    void leftRetryPreservesOriginalTimeAndDoesNotPublish() {
        participant.leave(now.minusHours(1));
        var response = processor.leave(3L, 2L);
        assertThat(response.status()).isEqualTo(SituationParticipantStatus.LEFT);
        assertThat(response.leftAt()).isEqualTo(now.minusHours(1));
        assertThat(participant.getLeftAt()).isEqualTo(response.leftAt());
        verifyNoInteractions(publisher);
    }

    @Test
    void removedRetryPreservesOriginalStateAndTimeWithoutEvent() {
        participant.remove(now.minusHours(2));
        var response = processor.leave(3L, 2L);
        assertThat(response.status()).isEqualTo(SituationParticipantStatus.REMOVED);
        assertThat(response.leftAt()).isEqualTo(now.minusHours(2));
        assertThat(participant.getStatus()).isEqualTo(SituationParticipantStatus.REMOVED);
        assertThat(participant.getLeftAt()).isEqualTo(response.leftAt());
        verifyNoInteractions(publisher);
    }

    @Test
    void endedSituationRejectsBothLeftAndRemovedRetries() {
        situation.end(now);
        participant.leave(now.minusHours(1));
        expectError(2L, SituationLeaveErrorCode.SITUATION_ENDED);
        var removed = participant(11L, now.minusDays(1));
        removed.remove(now.minusHours(2));
        when(store.findParticipantsForUpdate(3L, 2L)).thenReturn(List.of(removed));
        expectError(2L, SituationLeaveErrorCode.SITUATION_ENDED);
        assertThat(participant.getLeftAt()).isEqualTo(now.minusHours(1));
        assertThat(removed.getStatus()).isEqualTo(SituationParticipantStatus.REMOVED);
    }

    @Test
    void repeatedSuccessfulCallPublishesOnlyOnce() {
        var first = processor.leave(3L, 2L);
        assertThat(processor.leave(3L, 2L)).isEqualTo(first);
        verify(publisher, times(1)).publishEvent(any(SituationParticipantRemovedEvent.class));
        verifyNoMoreInteractions(publisher);
    }

    @Test
    void activeHistoryTakesPriorityOverMoreRecentInactiveHistory() {
        var recent = participant(11L, now);
        recent.remove(now);
        when(store.findParticipantsForUpdate(3L, 2L)).thenReturn(List.of(recent, participant));
        assertThat(processor.leave(3L, 2L).status()).isEqualTo(SituationParticipantStatus.LEFT);
        assertThat(participant.getLeftAt()).isEqualTo(now);
        assertThat(recent.getStatus()).isEqualTo(SituationParticipantStatus.REMOVED);
        verify(publisher).publishEvent(any(SituationParticipantRemovedEvent.class));
    }

    @Test
    void latestHistoryUsesJoinedAtBeforeId() {
        participant.leave(now.minusHours(1));
        var older = participant(100L, now.minusDays(2));
        older.remove(now.minusHours(2));
        when(store.findParticipantsForUpdate(3L, 2L)).thenReturn(List.of(participant, older));
        var response = processor.leave(3L, 2L);
        assertThat(response.status()).isEqualTo(SituationParticipantStatus.LEFT);
        assertThat(response.leftAt()).isEqualTo(now.minusHours(1));
        verifyNoInteractions(publisher);
    }

    @Test
    void sameJoinedAtUsesLargerIdRegardlessOfReturnOrder() {
        participant.leave(now.minusHours(1));
        var largerId = participant(11L, participant.getJoinedAt());
        largerId.remove(now.minusHours(2));
        when(store.findParticipantsForUpdate(3L, 2L)).thenReturn(List.of(largerId, participant));
        var response = processor.leave(3L, 2L);
        assertThat(response.status()).isEqualTo(SituationParticipantStatus.REMOVED);
        assertThat(response.leftAt()).isEqualTo(now.minusHours(2));
        verifyNoInteractions(publisher);
    }

    @Test
    void multipleActiveRowsAreIntegrityFailureWithoutMutationOrEvent() {
        var duplicate = participant(11L, now);
        when(store.findParticipantsForUpdate(3L, 2L)).thenReturn(List.of(participant, duplicate));
        assertThatThrownBy(() -> processor.leave(3L, 2L)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(List.of(participant, duplicate)).allSatisfy(p -> {
            assertThat(p.getStatus()).isEqualTo(SituationParticipantStatus.ACTIVE);
            assertThat(p.getLeftAt()).isNull();
        });
        verifyNoInteractions(publisher);
    }

    @Test
    void nullIdsFailBeforeStoreAccess() {
        assertThatNullPointerException().isThrownBy(() -> processor.leave(null, 2L));
        assertThatNullPointerException().isThrownBy(() -> processor.leave(3L, null));
        verifyNoInteractions(store, publisher);
    }

    private SituationParticipant participant(Long id, LocalDateTime joinedAt) {
        var value = SituationParticipant.create(situation, 2L, SituationParticipantRole.PARTICIPANT, joinedAt);
        ReflectionTestUtils.setField(value, "id", id);
        return value;
    }

    private void expectError(Long userId, ErrorCode code) {
        assertThatThrownBy(() -> processor.leave(3L, userId)).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
        verifyNoInteractions(publisher);
    }
}
