package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.entity.*;
import com.gyeot.domain.situation.repository.*;
import com.gyeot.domain.relation.repository.RelationshipQueryRepository;
import com.gyeot.domain.relation.entity.RelationshipStatus;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.domain.user.entity.AccountStatus;
import com.gyeot.global.exception.BusinessException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SituationParticipantAddServiceTest {
    private final SituationRepository situations=mock(SituationRepository.class);
    private final SituationParticipantRepository participants=mock(SituationParticipantRepository.class);
    private final RelationshipQueryRepository relations=mock(RelationshipQueryRepository.class);
    private final UserRepository users=mock(UserRepository.class);
    private final EntityManager em=mock(EntityManager.class);
    private final SituationParticipantAddService service=new SituationParticipantAddService(situations,participants,relations,users,em,
            Clock.fixed(Instant.parse("2026-11-15T01:00:00Z"),ZoneOffset.UTC));
    private Situation s;
    @BeforeEach void setUp(){
        s=Situation.create(10L,"유형","이름",LocalDate.of(2026,11,1),LocalDate.of(2026,11,30));
        ReflectionTestUtils.setField(s,"id",3L);
        when(situations.findForUpdate(3L)).thenReturn(Optional.of(s));
        when(participants.findHistoryForUpdate(3L,10L)).thenReturn(List.of(row(10L,SituationParticipantRole.OWNER)));
        when(participants.findHistoryForUpdate(3L,20L)).thenReturn(List.of());
        when(users.existsById(20L)).thenReturn(true);
        when(users.existsByIdAndAccountStatus(20L,AccountStatus.ACTIVE)).thenReturn(true);
        when(relations.existsBySourceUserIdAndTargetUserIdAndStatus(10L,20L,RelationshipStatus.ACTIVE)).thenReturn(true);
        when(participants.save(any(SituationParticipant.class))).thenAnswer(i->{var p=(SituationParticipant)i.getArgument(0);ReflectionTestUtils.setField(p,"id",7L);return p;});
    }
    @Test void createsParticipantUsingDirectedRelationAndSeoulTime(){
        var r=service.add(3L,10L,20L);
        assertThat(r.participantId()).isEqualTo(7L); assertThat(r.situationId()).isEqualTo(3L);
        assertThat(r.userId()).isEqualTo(20L);assertThat(r.role()).isEqualTo(SituationParticipantRole.PARTICIPANT);
        assertThat(r.status()).isEqualTo(SituationParticipantStatus.ACTIVE);
        assertThat(r.joinedAt()).isEqualTo(LocalDateTime.of(2026,11,15,10,0));
        var order=inOrder(situations,participants,users,relations);
        order.verify(situations).findForUpdate(3L);order.verify(participants).findHistoryForUpdate(3L,10L);
        order.verify(users).existsById(20L);order.verify(users).existsByIdAndAccountStatus(20L,AccountStatus.WITHDRAWN);
        order.verify(users).existsByIdAndAccountStatus(20L,AccountStatus.ACTIVE);
        order.verify(relations).existsBySourceUserIdAndTargetUserIdAndStatus(10L,20L,RelationshipStatus.ACTIVE);
        order.verify(participants).findHistoryForUpdate(3L,20L);order.verify(participants).save(any());
    }
    @Test void missingSituation(){when(situations.findForUpdate(3L)).thenReturn(Optional.empty());error("SITUATION_NOT_FOUND",10L,20L);verifyNoInteractions(users,relations);}
    @Test void nonOwnerAndOwnerWithoutActiveRowAreForbidden(){when(participants.findHistoryForUpdate(3L,10L)).thenReturn(List.of());error("SITUATION_FORBIDDEN",10L,20L);error("SITUATION_FORBIDDEN",99L,20L);verifyNoInteractions(users,relations);}
    @Test void endedStopsBeforeTargetValidation(){s.end(LocalDateTime.now());error("SITUATION_ENDED",10L,20L);verifyNoInteractions(users,relations);}
    @Test void selfAndNullAreInvalid(){error("INVALID_INPUT",10L,10L);error("INVALID_INPUT",10L,null);verifyNoInteractions(users,relations);}
    @Test void missingAndWithdrawnUsersAreRejected(){when(users.existsById(20L)).thenReturn(false);error("USER_NOT_FOUND",10L,20L);when(users.existsById(20L)).thenReturn(true);when(users.existsByIdAndAccountStatus(20L,AccountStatus.WITHDRAWN)).thenReturn(true);error("USER_ALREADY_WITHDRAWN",10L,20L);verifyNoInteractions(relations);}
    @Test void missingRelationshipIsNotBypassed(){when(relations.existsBySourceUserIdAndTargetUserIdAndStatus(10L,20L,RelationshipStatus.ACTIVE)).thenReturn(false);error("FORBIDDEN",10L,20L);verify(participants,never()).findHistoryForUpdate(3L,20L);}
    @Test void activeDuplicateIsConflict(){when(participants.findHistoryForUpdate(3L,20L)).thenReturn(List.of(row(20L,SituationParticipantRole.PARTICIPANT)));error("SITUATION_PARTICIPANT_ALREADY_EXISTS",10L,20L);}
    @Test void reentryPreservesBothInactiveHistories(){var left=row(20L,SituationParticipantRole.PARTICIPANT);var removed=row(20L,SituationParticipantRole.PARTICIPANT);var at=LocalDateTime.of(2026,11,14,9,0);left.leave(at);removed.remove(at);when(participants.findHistoryForUpdate(3L,20L)).thenReturn(List.of(left,removed));assertThat(service.add(3L,10L,20L).status()).isEqualTo(SituationParticipantStatus.ACTIVE);assertThat(left.getStatus()).isEqualTo(SituationParticipantStatus.LEFT);assertThat(removed.getStatus()).isEqualTo(SituationParticipantStatus.REMOVED);assertThat(left.getLeftAt()).isEqualTo(at);assertThat(removed.getLeftAt()).isEqualTo(at);verify(participants,never()).delete(any());}
    private SituationParticipant row(Long user,SituationParticipantRole role){return SituationParticipant.create(s,user,role,LocalDateTime.of(2026,11,1,9,0));}
    private void error(String code,Long caller,Long target){assertThatThrownBy(()->service.add(3L,caller,target)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode().getCode()).isEqualTo(code));verify(participants,never()).save(any());}
}
