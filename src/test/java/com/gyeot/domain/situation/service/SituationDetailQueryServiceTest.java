package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.entity.SituationParticipant;
import com.gyeot.domain.situation.entity.SituationParticipantRole;
import com.gyeot.domain.situation.entity.SituationParticipantStatus;
import com.gyeot.domain.situation.entity.SituationStatus;
import com.gyeot.domain.situation.exception.SituationQueryErrorCode;
import com.gyeot.domain.situation.repository.SituationParticipantRepository;
import com.gyeot.domain.situation.repository.SituationRepository;
import com.gyeot.global.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Service의 상태·권한 분기를 검증하며 실제 참여 상태 쿼리는 기존 DB 테스트 범위입니다. */
class SituationDetailQueryServiceTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 20, 9, 15, 30);
    private final SituationRepository repository = mock(SituationRepository.class);
    private final SituationParticipantRepository participants = mock(SituationParticipantRepository.class);
    private final SituationQueryService service = new SituationQueryService(repository, participants);

    @Test
    void allowsActiveOwnerAndMapsEveryBasicField() {
        Situation situation = givenSituation();
        givenParticipant(situation, 10L, SituationParticipantRole.OWNER, SituationParticipantStatus.ACTIVE);

        var response = service.detailActive(1L, 10L);

        assertEquals(1L, response.situationId());
        assertEquals("상세 이름", response.name());
        assertEquals("유형", response.situationType());
        assertEquals(START, response.startAt());
        assertEquals(START.plusHours(2), response.endAt());
        assertEquals(SituationStatus.ACTIVE, response.status());
        assertEquals(10L, response.creatorId());
        assertNull(response.endedAt());
        verify(participants).existsBySituation_IdAndUserIdAndStatus(1L, 10L, SituationParticipantStatus.ACTIVE);
    }

    @Test
    void allowsActiveOrdinaryParticipant() {
        givenParticipant(givenSituation(), 20L, SituationParticipantRole.PARTICIPANT, SituationParticipantStatus.ACTIVE);
        assertEquals(1L, service.detailActive(1L, 20L).situationId());
    }

    @Test
    void rejectsUnrelatedUser() {
        givenSituation();
        when(participants.existsBySituation_IdAndUserIdAndStatus(1L, 99L, SituationParticipantStatus.ACTIVE))
                .thenReturn(false);
        assertNotFound(99L);
    }

    @Test
    void rejectsLeftParticipant() {
        givenParticipant(givenSituation(), 20L, SituationParticipantRole.PARTICIPANT, SituationParticipantStatus.LEFT);
        assertNotFound(20L);
    }

    @Test
    void rejectsRemovedParticipant() {
        givenParticipant(givenSituation(), 20L, SituationParticipantRole.PARTICIPANT, SituationParticipantStatus.REMOVED);
        assertNotFound(20L);
    }

    @Test
    void rejectsEndedSituationBeforeCheckingParticipation() {
        givenSituation().end(START.plusHours(3));
        assertNotFound(10L);
        verifyNoInteractions(participants);
    }

    @Test
    void rejectsMissingSituationBeforeCheckingParticipation() {
        when(repository.findById(1L)).thenReturn(Optional.empty());
        assertNotFound(10L);
        verifyNoInteractions(participants);
    }

    @Test
    void ownerIdentityDoesNotBypassActiveParticipationRequirement() {
        givenSituation();
        assertNotFound(10L);
        verify(participants).existsBySituation_IdAndUserIdAndStatus(1L, 10L, SituationParticipantStatus.ACTIVE);
    }

    private Situation givenSituation() {
        Situation situation = Situation.create(10L, "유형", "상세 이름", START, START.plusHours(2));
        ReflectionTestUtils.setField(situation, "id", 1L);
        when(repository.findById(1L)).thenReturn(Optional.of(situation));
        return situation;
    }

    private void givenParticipant(Situation situation, Long userId, SituationParticipantRole role,
                                  SituationParticipantStatus status) {
        SituationParticipant participant = SituationParticipant.create(situation, userId, role, START);
        if (status == SituationParticipantStatus.LEFT) participant.leave(START.plusHours(1));
        if (status == SituationParticipantStatus.REMOVED) participant.remove(START.plusHours(1));
        when(participants.existsBySituation_IdAndUserIdAndStatus(1L, userId, SituationParticipantStatus.ACTIVE))
                .thenReturn(participant.getStatus() == SituationParticipantStatus.ACTIVE);
    }

    private void assertNotFound(Long userId) {
        BusinessException error = assertThrows(BusinessException.class, () -> service.detailActive(1L, userId));
        assertEquals(SituationQueryErrorCode.SITUATION_NOT_FOUND, error.getErrorCode());
        assertEquals("SITUATION_NOT_FOUND", error.getErrorCode().getCode());
    }
}
