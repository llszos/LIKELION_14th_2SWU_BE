package com.gyeot.domain.situation.service;

import com.gyeot.domain.carerequest.query.CareRequestSummaryReader;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.ObjectProvider;
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
import java.util.List;
import java.util.Map;
import java.time.Clock;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Service의 상태·권한 분기를 검증하며 실제 참여 상태 쿼리는 기존 DB 테스트 범위입니다. */
class SituationDetailQueryServiceTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 20, 9, 15, 30);
    private final SituationRepository repository = mock(SituationRepository.class);
    private final SituationParticipantRepository participants = mock(SituationParticipantRepository.class);
    private final CareRequestSummaryReader reader = mock(CareRequestSummaryReader.class);
    private final ObjectProvider<CareRequestSummaryReader> readers = mock(ObjectProvider.class);
    private final Clock clock = Clock.fixed(START.atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul"));
    private final SituationQueryService service = new SituationQueryService(repository, participants, readers, clock);

    @BeforeEach
    void emptyRequests() {
        when(readers.getIfAvailable()).thenReturn(reader);
        when(reader.getSituationStats(List.of(1L), START))
                .thenReturn(Map.of(1L, CareRequestSummaryReader.SituationRequestStats.empty()));
        when(reader.getSituationRequests(eq(1L), anyLong(), eq(START))).thenReturn(List.of());
    }

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

    @Test
    void forwardsActualViewerAndKeepsCOrderAndTargetMeaningWithSituationWideCount() {
        givenParticipant(givenSituation(), 20L, SituationParticipantRole.PARTICIPANT, SituationParticipantStatus.ACTIVE);
        var first = new CareRequestSummaryReader.RequestSummary(8L,
                com.gyeot.domain.carerequest.enums.RequestType.GENERAL,
                com.gyeot.domain.carerequest.enums.HelpType.HOSPITAL, START, START.plusHours(1), 10L, 20L,
                com.gyeot.domain.carerequest.query.RequestDisplayStatus.NO_ASSIGNEE);
        var second = new CareRequestSummaryReader.RequestSummary(9L,
                com.gyeot.domain.carerequest.enums.RequestType.GENERAL,
                com.gyeot.domain.carerequest.enums.HelpType.HOSPITAL, START.plusHours(1), START.plusHours(2), 10L, null,
                com.gyeot.domain.carerequest.query.RequestDisplayStatus.WAITING);
        when(reader.getSituationStats(List.of(1L), START))
                .thenReturn(Map.of(1L, new CareRequestSummaryReader.SituationRequestStats(2, 3, 4, 5, null)));
        when(reader.getSituationRequests(1L, 20L, START)).thenReturn(List.of(first, second));

        var response = service.detailActive(1L, 20L);

        assertEquals(14, response.activeRequestCount()); // 조회자별 목록 크기가 아니라 상황 전체 합계
        assertEquals(List.of(first, second), response.requests());
        assertEquals(20L, response.requests().getFirst().targetUserId()); // 담당자 ID로 바꾸지 않음
        verify(reader).getSituationStats(List.of(1L), START);
        verify(reader).getSituationRequests(1L, 20L, START);
        verifyNoMoreInteractions(reader);
        assertThrows(UnsupportedOperationException.class, () -> response.requests().clear());
    }

    @Test
    void missingReaderDoesNotPretendThereAreNoRequests() {
        givenParticipant(givenSituation(), 10L, SituationParticipantRole.OWNER, SituationParticipantStatus.ACTIVE);
        when(readers.getIfAvailable()).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> service.detailActive(1L, 10L));
    }

    @Test
    void missingStatisticsAndReaderFailuresAreNotHiddenAsZero() {
        givenParticipant(givenSituation(), 10L, SituationParticipantRole.OWNER, SituationParticipantStatus.ACTIVE);
        when(reader.getSituationStats(List.of(1L), START)).thenReturn(Map.of());
        assertThrows(NullPointerException.class, () -> service.detailActive(1L, 10L));
        var failure = new IllegalStateException("C reader failed");
        when(reader.getSituationStats(List.of(1L), START)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.detailActive(1L, 10L)));
    }

    @Test
    void nullRequestListIsNotHiddenAsEmpty() {
        givenParticipant(givenSituation(), 10L, SituationParticipantRole.OWNER, SituationParticipantStatus.ACTIVE);
        when(reader.getSituationRequests(1L, 10L, START)).thenReturn(null);
        assertThrows(NullPointerException.class, () -> service.detailActive(1L, 10L));
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
        verifyNoInteractions(reader);
    }
}
