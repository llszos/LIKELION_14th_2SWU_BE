package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.dto.internal.SituationParticipantInfo;
import com.gyeot.domain.situation.entity.*;
import com.gyeot.domain.situation.query.SituationParticipantReaderImpl;
import com.gyeot.domain.situation.repository.*;
import com.gyeot.global.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 실제 Reader 구현과 Mock Repository를 사용하며 JPQL 필터·정렬은 DB에서 미검증입니다. */
class SituationParticipantsQueryServiceTest {
    private final SituationParticipantRepository participants = mock(SituationParticipantRepository.class);
    private final SituationRepository situations = mock(SituationRepository.class);
    private final SituationParticipantsQueryService service = new SituationParticipantsQueryService(
            new SituationParticipantReaderImpl(situations, participants), participants);
    private final LocalDateTime joined = LocalDateTime.of(2026,11,15,9,0);

    @Test void activeViewerGetsOwnerAndParticipantBasicFieldsInRepositoryOrder() {
        when(participants.existsBySituation_IdAndUserIdAndStatus(3L,20L,SituationParticipantStatus.ACTIVE)).thenReturn(true);
        when(participants.findInfoBySituationAndStatus(3L,SituationParticipantStatus.ACTIVE)).thenReturn(List.of(
                new SituationParticipantInfo(5L,10L,SituationParticipantRole.OWNER,SituationParticipantStatus.ACTIVE,joined,null),
                new SituationParticipantInfo(7L,20L,SituationParticipantRole.PARTICIPANT,SituationParticipantStatus.ACTIVE,joined.plusHours(1),null)));
        var result = service.list(3L,20L);
        assertThat(result.items()).extracting(i -> i.participantId()).containsExactly(5L,7L);
        assertThat(result.items().getFirst().role()).isEqualTo(SituationParticipantRole.OWNER);
        assertThat(result.items().getLast().userId()).isEqualTo(20L);
        assertThat(result.items().getLast().joinedAt()).isEqualTo(joined.plusHours(1));
        assertThat(result.items()).allSatisfy(i -> {
            assertThat(i.status()).isEqualTo(SituationParticipantStatus.ACTIVE);
            assertThat(i.leftAt()).isNull();
        });
        verifyNoInteractions(situations); // ENDED 상태를 추가 제한하지 않음
    }

    @Test void ownerIdentityWithoutActiveRowAndOtherInaccessibleViewersAreNotFound() {
        for(Long viewer : List.of(10L,20L,99L)) {
            assertThatThrownBy(() -> service.list(3L,viewer)).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getErrorCode().getCode()).isEqualTo("SITUATION_NOT_FOUND"));
        }
        verify(participants,never()).findInfoBySituationAndStatus(anyLong(),any());
    }

    @Test void activeOwnerMayQueryWithoutSituationStatusRestriction() {
        when(participants.existsBySituation_IdAndUserIdAndStatus(3L,10L,SituationParticipantStatus.ACTIVE)).thenReturn(true);
        when(participants.findInfoBySituationAndStatus(3L,SituationParticipantStatus.ACTIVE)).thenReturn(List.of());
        assertThat(service.list(3L,10L).items()).isEmpty();
        verifyNoInteractions(situations);
        verify(participants).findInfoBySituationAndStatus(3L,SituationParticipantStatus.ACTIVE);
    }
}
