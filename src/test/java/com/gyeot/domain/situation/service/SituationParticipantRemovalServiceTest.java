package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.entity.*;
import com.gyeot.domain.situation.event.SituationParticipantRemovedEvent;
import com.gyeot.domain.situation.repository.*;
import com.gyeot.global.exception.BusinessException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 실제 Processor와 Mock 저장소를 사용합니다. DB 잠금·롤백 테스트는 아닙니다. */
class SituationParticipantRemovalServiceTest {
    private final SituationRepository situations = mock(SituationRepository.class);
    private final SituationParticipantRepository participants = mock(SituationParticipantRepository.class);
    private final SituationParticipantLockReader reader = mock(SituationParticipantLockReader.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final EntityManager em = mock(EntityManager.class);
    private final SituationParticipantRemovalProcessor processor = new SituationParticipantRemovalProcessor(publisher,
            Clock.fixed(Instant.parse("2026-11-15T01:00:00Z"), ZoneId.of("Asia/Seoul")));
    private final SituationParticipantRemovalService service = new SituationParticipantRemovalService(
            situations, participants, reader, processor, em);
    private Situation situation;
    private SituationParticipant owner;
    private SituationParticipant target;

    @BeforeEach void setUp() {
        situation = Situation.create(10L, "유형", "이름", LocalDate.of(2026,11,1), LocalDate.of(2026,11,30));
        ReflectionTestUtils.setField(situation, "id", 3L);
        owner = participant(10L, SituationParticipantRole.OWNER, 5L);
        target = participant(20L, SituationParticipantRole.PARTICIPANT, 7L);
        when(situations.findForUpdate(3L)).thenReturn(Optional.of(situation));
        when(participants.findHistoryForUpdate(3L, 10L)).thenReturn(List.of(owner));
        when(reader.findForUpdate(3L, 7L)).thenReturn(Optional.of(target));
    }

    @Test void removesUsingParentThenCallerThenTargetLocksAndReturnsResult() {
        var result = service.remove(3L, 7L, 10L);
        assertThat(result.participantId()).isEqualTo(7L);
        assertThat(result.status()).isEqualTo(SituationParticipantStatus.REMOVED);
        assertThat(result.leftAt()).isEqualTo(target.getLeftAt());
        var order = inOrder(situations, participants, em, reader, publisher);
        order.verify(situations).findForUpdate(3L);
        order.verify(em).refresh(situation, LockModeType.PESSIMISTIC_WRITE);
        order.verify(participants).findHistoryForUpdate(3L, 10L);
        order.verify(em).refresh(owner, LockModeType.PESSIMISTIC_WRITE);
        order.verify(reader).findForUpdate(3L, 7L);
        order.verify(publisher).publishEvent(any(SituationParticipantRemovedEvent.class));
    }
    @Test void missingSituationStopsBeforePermissionOrTargetQuery() {
        when(situations.findForUpdate(3L)).thenReturn(Optional.empty());
        error("SITUATION_NOT_FOUND",10L);
        verifyNoInteractions(participants, reader);
    }
    @Test void creatorWithoutActiveOwnerRowIsForbiddenEvenWhenEnded() {
        when(participants.findHistoryForUpdate(3L, 10L)).thenReturn(List.of());
        situation.end(LocalDateTime.now());
        error("SITUATION_FORBIDDEN",10L);
        verifyNoInteractions(reader);
    }
    @Test void ordinaryParticipantIsForbidden() {
        when(participants.findHistoryForUpdate(3L,20L)).thenReturn(List.of(target));
        error("SITUATION_FORBIDDEN",20L);
        verifyNoInteractions(reader);
    }
    @Test void endedSituationStopsBeforeTargetQuery() {
        situation.end(LocalDateTime.now());
        error("SITUATION_ENDED",10L);
        verifyNoInteractions(reader);
    }
    @Test void missingOrOtherSituationTargetIsNotFound() {
        when(reader.findForUpdate(3L,7L)).thenReturn(Optional.empty());
        error("SITUATION_PARTICIPANT_NOT_FOUND",10L);
    }
    @Test void ownerTargetIsConflict() {
        when(reader.findForUpdate(3L,7L)).thenReturn(Optional.of(owner));
        error("SITUATION_OWNER_REMOVAL_NOT_ALLOWED",10L);
    }
    @Test void leftAndRemovedRetriesPreserveTimeAndDoNotPublish() {
        var original = LocalDateTime.of(2026,11,14,9,0);
        target.leave(original);
        assertThat(service.remove(3L,7L,10L).status()).isEqualTo(SituationParticipantStatus.LEFT);
        var removed = participant(20L, SituationParticipantRole.PARTICIPANT,7L);
        removed.remove(original);
        when(reader.findForUpdate(3L,7L)).thenReturn(Optional.of(removed));
        assertThat(service.remove(3L,7L,10L).leftAt()).isEqualTo(original);
        verifyNoInteractions(publisher);
    }
    @Test void repeatedRemovalPublishesOnce() {
        var first = service.remove(3L,7L,10L);
        assertThat(service.remove(3L,7L,10L)).isEqualTo(first);
        verify(publisher).publishEvent(any(SituationParticipantRemovedEvent.class));
        verifyNoMoreInteractions(publisher);
    }
    @Test void listenerFailurePropagatesWithoutClaimingDatabaseRollback() {
        var failure = new IllegalStateException("listener failed");
        doThrow(failure).when(publisher).publishEvent(any(SituationParticipantRemovedEvent.class));
        assertThatThrownBy(() -> service.remove(3L,7L,10L)).isSameAs(failure);
        assertThat(target.getStatus()).isEqualTo(SituationParticipantStatus.REMOVED);
    }
    private SituationParticipant participant(Long userId, SituationParticipantRole role, Long id) {
        var p = SituationParticipant.create(situation,userId,role,LocalDateTime.of(2026,11,1,9,0));
        ReflectionTestUtils.setField(p,"id",id);
        return p;
    }
    private void error(String code,Long caller) {
        assertThatThrownBy(() -> service.remove(3L,7L,caller)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode().getCode()).isEqualTo(code));
        assertThat(target.getStatus()).isEqualTo(SituationParticipantStatus.ACTIVE);
        verifyNoInteractions(publisher);
    }
}
