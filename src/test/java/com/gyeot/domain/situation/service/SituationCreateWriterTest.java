package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.entity.SituationParticipant;
import com.gyeot.domain.situation.entity.SituationParticipantRole;
import com.gyeot.domain.situation.entity.SituationParticipantStatus;
import com.gyeot.domain.situation.entity.SituationStatus;
import com.gyeot.domain.situation.repository.SituationParticipantRepository;
import com.gyeot.domain.situation.repository.SituationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Repository Mock으로 저장 인자·순서·오류 전파만 검증합니다. DB 롤백 테스트가 아닙니다. */
class SituationCreateWriterTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 20, 9, 0);
    private static final LocalDateTime END = START.plusDays(1);
    private final SituationRepository situations = mock(SituationRepository.class);
    private final SituationParticipantRepository participants = mock(SituationParticipantRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-20T00:30:45.123456789Z"), ZoneOffset.UTC);
    private final SituationCreateWriter writer = new SituationCreateWriter(situations, participants, clock);

    @Test
    void savesSituationThenOwnerAndReturnsPersistedIdentityAndStatus() {
        assignIdOnSave();

        var response = writer.save(10L, "검증된 종류", "회복 지원", START, END);

        var saved = ArgumentCaptor.forClass(Situation.class);
        var owner = ArgumentCaptor.forClass(SituationParticipant.class);
        var order = inOrder(situations, participants);
        order.verify(situations).save(saved.capture());
        order.verify(participants).save(owner.capture());
        order.verifyNoMoreInteractions();
        assertEquals(10L, saved.getValue().getCreatorId());
        assertEquals("검증된 종류", saved.getValue().getSituationType());
        assertEquals("회복 지원", saved.getValue().getTitle());
        assertEquals(START, saved.getValue().getStartAt());
        assertEquals(END, saved.getValue().getEndAt());
        assertNull(saved.getValue().getEndedAt());
        assertSame(saved.getValue(), owner.getValue().getSituation());
        assertEquals(10L, owner.getValue().getUserId());
        assertEquals(SituationParticipantRole.OWNER, owner.getValue().getRole());
        assertEquals(SituationParticipantStatus.ACTIVE, owner.getValue().getStatus());
        assertNull(owner.getValue().getLeftAt());
        assertEquals(7L, response.situationId());
        assertEquals(10L, response.creatorId());
        assertEquals(SituationStatus.ACTIVE, response.status());
    }

    @Test
    void usesSeoulParticipationTimeEvenWhenInjectedClockUsesUtc() {
        assignIdOnSave();
        writer.save(10L, "검증된 종류", "회복 지원", START, END);
        var owner = ArgumentCaptor.forClass(SituationParticipant.class);
        verify(participants).save(owner.capture());
        assertEquals(LocalDateTime.of(2026, 11, 20, 9, 30, 45, 123456000), owner.getValue().getJoinedAt());
    }

    @Test
    void invalidPeriodDoesNotReachEitherRepository() {
        assertThrows(IllegalArgumentException.class, () -> writer.save(10L, "종류", "이름", END, START));
        verifyNoInteractions(situations, participants);
    }

    @Test
    void propagatesSituationSaveFailureWithoutSavingOwner() {
        var failure = new IllegalStateException("situation save failed");
        when(situations.save(any(Situation.class))).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> writer.save(10L, "종류", "이름", START, END)));
        verifyNoInteractions(participants);
    }

    @Test
    void propagatesOwnerSaveFailureInsteadOfReturningSuccess() {
        assignIdOnSave();
        var failure = new IllegalStateException("owner save failed");
        when(participants.save(any(SituationParticipant.class))).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> writer.save(10L, "종류", "이름", START, END)));
        verify(situations).save(any(Situation.class));
    }

    private void assignIdOnSave() {
        when(situations.save(any(Situation.class))).thenAnswer(invocation -> {
            Situation situation = invocation.getArgument(0);
            ReflectionTestUtils.setField(situation, "id", 7L);
            return situation;
        });
    }
}
