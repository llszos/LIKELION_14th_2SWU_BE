package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.entity.*;
import com.gyeot.domain.situation.repository.SituationParticipantRepository;
import com.gyeot.domain.situation.repository.SituationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 호출 인자·순서만 검증합니다. JPQL 필터와 실제 DB 잠금은 통합 테스트가 필요합니다. */
class SituationParticipantLockReaderTest {
    private final SituationRepository situations = mock(SituationRepository.class);
    private final SituationParticipantRepository participants = mock(SituationParticipantRepository.class);
    private final EntityManager em = mock(EntityManager.class);
    private final SituationParticipantLockReader reader = new SituationParticipantLockReader(situations, participants, em);
    private final Situation situation = Situation.create(10L, "유형", "이름",
            LocalDateTime.of(2026, 11, 15, 0, 0), LocalDateTime.of(2026, 11, 21, 0, 0));

    @Test
    void locksParentBeforeLookingUpParticipationPrimaryKeyAndReloading() {
        var participant = SituationParticipant.create(situation, 20L, SituationParticipantRole.PARTICIPANT,
                LocalDateTime.of(2026, 11, 15, 9, 0));
        when(situations.findForUpdate(3L)).thenReturn(Optional.of(situation));
        when(participants.findBySituationAndParticipantForUpdate(3L, 7L)).thenReturn(Optional.of(participant));

        var current = SituationParticipant.create(situation, 20L, SituationParticipantRole.PARTICIPANT,
                LocalDateTime.of(2026, 11, 15, 9, 0));
        current.leave(LocalDateTime.of(2026, 11, 15, 10, 0));
        when(em.find(SituationParticipant.class, 7L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(current);

        assertThat(reader.findForUpdate(3L, 7L)).containsSame(current);

        var order = inOrder(situations, participants, em);
        order.verify(situations).findForUpdate(3L);
        order.verify(participants).findBySituationAndParticipantForUpdate(3L, 7L);
        order.verify(em).detach(participant);
        order.verify(em).find(SituationParticipant.class, 7L, LockModeType.PESSIMISTIC_WRITE);
        order.verifyNoMoreInteractions();
        assertThat(participant.getStatus()).isEqualTo(SituationParticipantStatus.ACTIVE);
        assertThat(participant.getLeftAt()).isNull();
    }

    @Test
    void missingParentDoesNotQueryOrLockParticipation() {
        when(situations.findForUpdate(3L)).thenReturn(Optional.empty());
        assertThat(reader.findForUpdate(3L, 7L)).isEmpty();
        verifyNoInteractions(participants, em);
    }

    @Test
    void missingParticipationReturnsEmptyWithoutRefreshingParticipation() {
        when(situations.findForUpdate(3L)).thenReturn(Optional.of(situation));
        when(participants.findBySituationAndParticipantForUpdate(3L, 999L)).thenReturn(Optional.empty());
        assertThat(reader.findForUpdate(3L, 999L)).isEmpty();
        verifyNoInteractions(em);
        verify(participants).findBySituationAndParticipantForUpdate(3L, 999L);
        verifyNoMoreInteractions(participants);
    }
}
