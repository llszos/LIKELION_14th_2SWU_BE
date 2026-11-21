package com.gyeot.domain.situation.service;

import com.gyeot.domain.relation.entity.RelationshipType;
import com.gyeot.domain.relation.event.RelationshipEndedEvent;
import com.gyeot.domain.situation.entity.*;
import com.gyeot.domain.situation.event.*;
import com.gyeot.domain.situation.repository.SituationCleanupRepository;
import com.gyeot.domain.user.event.UserWithdrawnEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SituationMembershipCleanupServiceTest {
    private final SituationCleanupRepository repository = mock(SituationCleanupRepository.class);
    private final EntityManager em = mock(EntityManager.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final SituationMembershipCleanupService service = new SituationMembershipCleanupService(repository, em, publisher);
    private final LocalDateTime occurredAt = LocalDateTime.of(2026, 11, 16, 12, 0);
    private final Map<Long, Situation> situations = new HashMap<>();
    private final Map<Long, SituationParticipant> participants = new HashMap<>();
    private long nextId = 1;

    @BeforeEach
    void setUp() {
        when(em.find(eq(Situation.class), anyLong(), eq(LockModeType.PESSIMISTIC_WRITE)))
                .thenAnswer(i -> situations.get(i.getArgument(1)));
        when(em.find(eq(SituationParticipant.class), anyLong(), eq(LockModeType.PESSIMISTIC_WRITE)))
                .thenAnswer(i -> participants.get(i.getArgument(1)));
    }

    @Test
    void myPersonRemovesOnlyTargetInSourceOwnedSituationAndPreservesOwner() {
        var situation = situation(10L);
        var owner = participant(situation, 10L, SituationParticipantRole.OWNER);
        var target = participant(situation, 20L, SituationParticipantRole.PARTICIPANT);
        var other = participant(situation, 30L, SituationParticipantRole.PARTICIPANT);
        when(repository.lockOwnedSituations(List.of(10L))).thenReturn(List.of(situation));
        service.relationshipEnded(event(RelationshipType.MY_PERSON));
        assertThat(target.getStatus()).isEqualTo(SituationParticipantStatus.REMOVED);
        assertThat(target.getLeftAt()).isEqualTo(occurredAt);
        assertThat(owner.getStatus()).isEqualTo(SituationParticipantStatus.ACTIVE);
        assertThat(other.getStatus()).isEqualTo(SituationParticipantStatus.ACTIVE);
        assertThat(situation.getStatus()).isEqualTo(SituationStatus.ACTIVE);
        verify(repository).lockOwnedSituations(List.of(10L));
        verify(publisher).publishEvent(new SituationParticipantRemovedEvent(situation.getId(), 20L,
                SituationParticipantRemovalReason.RELATIONSHIP_ENDED, occurredAt));
    }

    @Test
    void guardianRemovesBothDirectionsWithExactCContract() {
        var first = situation(10L);
        participant(first, 10L, SituationParticipantRole.OWNER);
        var target = participant(first, 20L, SituationParticipantRole.PARTICIPANT);
        var second = situation(20L);
        participant(second, 20L, SituationParticipantRole.OWNER);
        var reverse = participant(second, 10L, SituationParticipantRole.PARTICIPANT);
        when(repository.lockOwnedSituations(List.of(10L, 20L))).thenReturn(List.of(first, second));
        service.relationshipEnded(event(RelationshipType.GUARDIAN));
        assertThat(target.getStatus()).isEqualTo(SituationParticipantStatus.REMOVED);
        assertThat(reverse.getStatus()).isEqualTo(SituationParticipantStatus.REMOVED);
        var events = ArgumentCaptor.forClass(SituationParticipantRemovedEvent.class);
        verify(publisher, times(2)).publishEvent(events.capture());
        assertThat(events.getAllValues()).extracting(SituationParticipantRemovedEvent::userId)
                .containsExactly(20L, 10L);
        assertThat(events.getAllValues()).allSatisfy(e -> {
            assertThat(e.reason()).isEqualTo(SituationParticipantRemovalReason.RELATIONSHIP_ENDED);
            assertThat(e.occurredAt()).isEqualTo(occurredAt);
        });
    }

    @Test
    void repeatedRelationshipEventDoesNotChangeFirstTimeOrPublishAgain() {
        var s = situation(10L);
        participant(s, 10L, SituationParticipantRole.OWNER);
        var p = participant(s, 20L, SituationParticipantRole.PARTICIPANT);
        when(repository.lockOwnedSituations(anyCollection())).thenReturn(List.of(s));
        service.relationshipEnded(event(RelationshipType.MY_PERSON));
        service.relationshipEnded(new RelationshipEndedEvent(7L, 10L, 20L, RelationshipType.MY_PERSON, occurredAt.plusHours(1)));
        assertThat(p.getLeftAt()).isEqualTo(occurredAt);
        verify(publisher, times(1)).publishEvent(any(SituationParticipantRemovedEvent.class));
    }

    @Test
    void leftRemovedAndEndedAreNotModified() {
        var active = situation(10L);
        participant(active, 10L, SituationParticipantRole.OWNER);
        var left = participant(active, 20L, SituationParticipantRole.PARTICIPANT);
        left.leave(occurredAt.minusHours(1));
        var removed = participant(active, 20L, SituationParticipantRole.PARTICIPANT);
        removed.remove(occurredAt.minusHours(2));
        var ended = situation(10L);
        ended.end(occurredAt.minusMinutes(1));
        var unchanged = participant(ended, 20L, SituationParticipantRole.PARTICIPANT);
        when(repository.lockOwnedSituations(anyCollection())).thenReturn(List.of(active, ended));
        service.relationshipEnded(event(RelationshipType.MY_PERSON));
        assertThat(left.getStatus()).isEqualTo(SituationParticipantStatus.LEFT);
        assertThat(left.getLeftAt()).isEqualTo(occurredAt.minusHours(1));
        assertThat(removed.getLeftAt()).isEqualTo(occurredAt.minusHours(2));
        assertThat(unchanged.getStatus()).isEqualTo(SituationParticipantStatus.ACTIVE);
        verifyNoInteractions(publisher);
    }

    @Test
    void relationshipEventRemovesParticipationThatJoinedDuringCleanup() {
        var s = situation(10L);
        participant(s, 10L, SituationParticipantRole.OWNER);
        var p = participant(s, 20L, SituationParticipantRole.PARTICIPANT);
        ReflectionTestUtils.setField(p, "joinedAt", occurredAt.plusMinutes(1));
        when(repository.lockOwnedSituations(anyCollection())).thenReturn(List.of(s));
        service.relationshipEnded(event(RelationshipType.MY_PERSON));
        assertThat(p.getStatus()).isEqualTo(SituationParticipantStatus.REMOVED);
        assertThat(p.getLeftAt()).isEqualTo(p.getJoinedAt());
        verify(publisher).publishEvent(new SituationParticipantRemovedEvent(s.getId(), 20L,
                SituationParticipantRemovalReason.RELATIONSHIP_ENDED, p.getJoinedAt()));
    }

    @Test
    void noActiveOwnerMeansNoCleanup() {
        var s = situation(10L);
        var p = participant(s, 20L, SituationParticipantRole.PARTICIPANT);
        when(repository.lockOwnedSituations(anyCollection())).thenReturn(List.of(s));
        service.relationshipEnded(event(RelationshipType.MY_PERSON));
        assertThat(p.getStatus()).isEqualTo(SituationParticipantStatus.ACTIVE);
        verifyNoInteractions(publisher);
    }

    @Test
    void withdrawalLeavesOnlyActiveGeneralParticipantWithoutSecondaryEvent() {
        var s = situation(30L);
        var p = participant(s, 20L, SituationParticipantRole.PARTICIPANT);
        var other = participant(s, 40L, SituationParticipantRole.PARTICIPANT);
        when(repository.lockParticipatedSituations(20L)).thenReturn(List.of(s));
        service.userWithdrawn(new UserWithdrawnEvent(20L, occurredAt));
        service.userWithdrawn(new UserWithdrawnEvent(20L, occurredAt.plusHours(1)));
        assertThat(p.getStatus()).isEqualTo(SituationParticipantStatus.LEFT);
        assertThat(p.getLeftAt()).isEqualTo(occurredAt);
        assertThat(other.getStatus()).isEqualTo(SituationParticipantStatus.ACTIVE);
        verifyNoInteractions(publisher);
    }

    @Test
    void withdrawalPreservesOwnedAndEndedSituationsAndOldStates() {
        var owned = situation(20L);
        var owner = participant(owned, 20L, SituationParticipantRole.OWNER);
        var ended = situation(30L);
        var inEnded = participant(ended, 20L, SituationParticipantRole.PARTICIPANT);
        ended.end(occurredAt.minusHours(1));
        var active = situation(40L);
        var removed = participant(active, 20L, SituationParticipantRole.PARTICIPANT);
        removed.remove(occurredAt.minusHours(2));
        when(repository.lockParticipatedSituations(20L)).thenReturn(List.of(owned, ended, active));
        service.userWithdrawn(new UserWithdrawnEvent(20L, occurredAt));
        assertThat(owner.getStatus()).isEqualTo(SituationParticipantStatus.ACTIVE);
        assertThat(owned.getStatus()).isEqualTo(SituationStatus.ACTIVE);
        assertThat(inEnded.getStatus()).isEqualTo(SituationParticipantStatus.ACTIVE);
        assertThat(removed.getLeftAt()).isEqualTo(occurredAt.minusHours(2));
        verifyNoInteractions(publisher);
    }

    @Test
    void downstreamFailurePropagatesForOriginalTransactionRollback() {
        var s = situation(10L);
        participant(s, 10L, SituationParticipantRole.OWNER);
        participant(s, 20L, SituationParticipantRole.PARTICIPANT);
        when(repository.lockOwnedSituations(anyCollection())).thenReturn(List.of(s));
        doThrow(new IllegalStateException("C failed")).when(publisher).publishEvent(any(SituationParticipantRemovedEvent.class));
        assertThatThrownBy(() -> service.relationshipEnded(event(RelationshipType.MY_PERSON)))
                .isInstanceOf(IllegalStateException.class).hasMessage("C failed");
    }

    @Test
    void corruptDuplicateActiveRowsFailBeforeAnyTransitionOrEvent() {
        var s = situation(10L);
        participant(s, 10L, SituationParticipantRole.OWNER);
        var first = participant(s, 20L, SituationParticipantRole.PARTICIPANT);
        var second = participant(s, 20L, SituationParticipantRole.PARTICIPANT);
        when(repository.lockOwnedSituations(anyCollection())).thenReturn(List.of(s));
        assertThatThrownBy(() -> service.relationshipEnded(event(RelationshipType.MY_PERSON)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(first.getStatus()).isEqualTo(SituationParticipantStatus.ACTIVE);
        assertThat(second.getStatus()).isEqualTo(SituationParticipantStatus.ACTIVE);
        verifyNoInteractions(publisher);
    }

    @Test
    void emptyMatchesAreNoOp() {
        service.relationshipEnded(event(RelationshipType.MY_PERSON));
        service.userWithdrawn(new UserWithdrawnEvent(20L, occurredAt));
        verifyNoInteractions(publisher);
    }

    private RelationshipEndedEvent event(RelationshipType type) {
        return new RelationshipEndedEvent(7L, 10L, 20L, type, occurredAt);
    }

    private Situation situation(Long creatorId) {
        var s = Situation.create(creatorId, "RECOVERY", "회복", LocalDateTime.of(2026, 11, 1, 0, 0), LocalDateTime.of(2026, 12, 1, 0, 0));
        ReflectionTestUtils.setField(s, "id", nextId++);
        situations.put(s.getId(), s);
        when(repository.lockParticipants(s.getId())).thenAnswer(i -> participants.values().stream()
                .filter(p -> p.getSituation().getId().equals(s.getId())).sorted(Comparator.comparing(SituationParticipant::getId)).toList());
        return s;
    }

    private SituationParticipant participant(Situation s, Long userId, SituationParticipantRole role) {
        var p = SituationParticipant.create(s, userId, role, occurredAt.minusDays(1));
        ReflectionTestUtils.setField(p, "id", nextId++);
        participants.put(p.getId(), p);
        return p;
    }
}
