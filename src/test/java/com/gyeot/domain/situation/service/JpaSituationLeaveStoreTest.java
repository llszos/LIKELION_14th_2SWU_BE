package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.entity.*;
import com.gyeot.domain.situation.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JpaSituationLeaveStoreTest {
    @Test void processorUsesReloadedHistoryAndPreservesFirstLeaveWithoutEvent() {
        var situations = mock(SituationRepository.class);
        var participants = mock(SituationParticipantRepository.class);
        var em = mock(EntityManager.class);
        var publisher = mock(ApplicationEventPublisher.class);
        var oldSituation = situation();
        var currentSituation = situation();
        var stale = participant(oldSituation);
        var current = participant(currentSituation);
        var firstLeftAt = LocalDateTime.of(2026, 11, 15, 9, 0);
        current.leave(firstLeftAt);
        when(situations.findForUpdate(1L)).thenReturn(Optional.of(oldSituation));
        when(em.find(Situation.class, 1L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(currentSituation);
        when(participants.findHistoryForUpdate(1L, 2L)).thenReturn(List.of(stale));
        when(em.find(SituationParticipant.class, 3L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(current);
        var store = new JpaSituationLeaveStore(situations, participants, em);
        var processor = new SituationLeaveProcessor(store, publisher, Clock.systemUTC());

        var response = processor.leave(1L, 2L);

        assertThat(response.leftAt()).isEqualTo(firstLeftAt);
        assertThat(response.status()).isEqualTo(SituationParticipantStatus.LEFT);
        assertThat(stale.getStatus()).isEqualTo(SituationParticipantStatus.ACTIVE);
        verifyNoInteractions(publisher);
        var order = inOrder(situations, participants, em);
        order.verify(situations).findForUpdate(1L);
        order.verify(em).detach(oldSituation);
        order.verify(em).find(Situation.class, 1L, LockModeType.PESSIMISTIC_WRITE);
        order.verify(participants).findHistoryForUpdate(1L, 2L);
        order.verify(em).detach(stale);
        order.verify(em).find(SituationParticipant.class, 3L, LockModeType.PESSIMISTIC_WRITE);
        verifyNoMoreInteractions(em);
    }

    @Test void missingSituationDoesNotDetachOrReloadAnything() {
        var situations = mock(SituationRepository.class);
        var participants = mock(SituationParticipantRepository.class);
        var em = mock(EntityManager.class);
        when(situations.findForUpdate(1L)).thenReturn(Optional.empty());
        assertThat(new JpaSituationLeaveStore(situations, participants, em).findSituationForUpdate(1L)).isEmpty();
        verifyNoInteractions(em, participants);
    }

    private Situation situation() {
        var value = Situation.create(1L, "돌봄", "동행", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));
        ReflectionTestUtils.setField(value, "id", 1L);
        return value;
    }
    private SituationParticipant participant(Situation situation) {
        var value = SituationParticipant.create(situation, 2L, SituationParticipantRole.PARTICIPANT,
                LocalDateTime.of(2026, 11, 1, 9, 0));
        ReflectionTestUtils.setField(value, "id", 3L);
        return value;
    }
}
