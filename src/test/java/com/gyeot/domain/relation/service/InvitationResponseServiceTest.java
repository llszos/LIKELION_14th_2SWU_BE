package com.gyeot.domain.relation.service;

import com.gyeot.domain.relation.dto.response.InvitationDetailsResponse;
import com.gyeot.domain.relation.entity.EmergencyContact;
import com.gyeot.domain.relation.entity.Invitation;
import com.gyeot.domain.relation.entity.InvitationStatus;
import com.gyeot.domain.relation.entity.InvitationType;
import com.gyeot.domain.relation.entity.Relationship;
import com.gyeot.domain.relation.entity.RelationshipStatus;
import com.gyeot.domain.relation.entity.RelationshipType;
import com.gyeot.domain.relation.exception.InvitationErrorCode;
import com.gyeot.domain.relation.repository.EmergencyContactRepository;
import com.gyeot.domain.relation.repository.InvitationRepository;
import com.gyeot.domain.relation.repository.RelationshipRepository;
import com.gyeot.domain.user.entity.User;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvitationResponseServiceTest {

    private static final Long INVITER_ID = 1L;
    private static final Long INVITEE_ID = 2L;
    private static final String TOKEN = "test-invitation-token";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 9, 12, 0);

    private InvitationRepository invitationRepository;
    private RelationshipRepository relationshipRepository;
    private EmergencyContactRepository emergencyContactRepository;
    private UserRepository userRepository;
    private AdjustableClock clock;
    private InvitationTokenGenerator tokenGenerator;
    private InvitationService service;
    private Invitation invitation;
    private User inviter;
    private User invitee;

    @BeforeEach
    void setUp() {
        invitationRepository = mock(InvitationRepository.class);
        relationshipRepository = mock(RelationshipRepository.class);
        emergencyContactRepository = mock(EmergencyContactRepository.class);
        userRepository = mock(UserRepository.class);
        clock = new AdjustableClock(Instant.parse("2026-11-09T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        tokenGenerator = new InvitationTokenGenerator();
        service = new InvitationService(
                invitationRepository,
                relationshipRepository,
                emergencyContactRepository,
                userRepository,
                tokenGenerator,
                clock,
                "https://example.com/invitations"
        );

        invitation = invitation(InvitationStatus.PENDING, NOW.plusMinutes(10));
        inviter = user(INVITER_ID, "발신자");
        invitee = user(INVITEE_ID, "수신자");
        when(invitationRepository.findByTokenHash(tokenGenerator.hash(TOKEN))).thenReturn(Optional.of(invitation));
        when(invitationRepository.findByTokenHashForUpdate(tokenGenerator.hash(TOKEN))).thenReturn(Optional.of(invitation));
        when(userRepository.findById(INVITER_ID)).thenReturn(Optional.of(inviter));
        when(userRepository.findByIdForUpdate(INVITER_ID)).thenReturn(Optional.of(inviter));
        when(userRepository.findByIdForUpdate(INVITEE_ID)).thenReturn(Optional.of(invitee));
    }

    @Test
    void 만료된_초대_조회는_응답만_EXPIRED로_보여주고_DB_상태는_바꾸지_않는다() {
        invitation = invitation(InvitationStatus.PENDING, NOW.minusSeconds(1));
        when(invitationRepository.findByTokenHash(tokenGenerator.hash(TOKEN))).thenReturn(Optional.of(invitation));

        InvitationDetailsResponse response = service.getInvitation(TOKEN);

        assertEquals(InvitationStatus.EXPIRED, response.status());
        assertFalse(response.canAccept());
        assertEquals(InvitationStatus.PENDING, invitation.getStatus());
        verify(invitationRepository, never()).save(any());
    }

    @Test
    void 만료된_조회에서도_이미_수락된_상태는_유지한다() {
        invitation = invitation(InvitationStatus.ACCEPTED, NOW.minusSeconds(1));
        when(invitationRepository.findByTokenHash(tokenGenerator.hash(TOKEN))).thenReturn(Optional.of(invitation));

        InvitationDetailsResponse response = service.getInvitation(TOKEN);

        assertEquals(InvitationStatus.ACCEPTED, response.status());
        assertFalse(response.canAccept());
        assertEquals(InvitationStatus.ACCEPTED, invitation.getStatus());
    }

    @Test
    void 비로그인_초대_조회는_수락_불가로_안내한다() {
        InvitationDetailsResponse response = service.getInvitation(TOKEN);

        assertFalse(response.canAccept());
        assertEquals("AUTHENTICATION_REQUIRED", response.unavailableReason());
    }

    @Test
    void 보호자_요청_조회는_인원_초과를_수락_불가_사유로_반환한다() {
        invitation = guardianInvitation();
        when(invitationRepository.findByTokenHash(tokenGenerator.hash(TOKEN)))
                .thenReturn(Optional.of(invitation));
        when(relationshipRepository.countBySourceUserIdAndRelationshipTypeAndStatus(
                INVITEE_ID, RelationshipType.GUARDIAN, RelationshipStatus.ACTIVE)).thenReturn(3L);

        InvitationDetailsResponse response = service.getInvitation(TOKEN, INVITEE_ID);

        assertFalse(response.canAccept());
        assertEquals("GUARDIAN_LIMIT_REACHED", response.unavailableReason());
    }

    @Test
    void 보호자_요청_생성은_발신자_인원_초과를_막는다() {
        when(relationshipRepository.countBySourceUserIdAndRelationshipTypeAndStatus(
                INVITER_ID, RelationshipType.GUARDIAN, RelationshipStatus.ACTIVE)).thenReturn(3L);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.createInvitation(INVITER_ID, InvitationType.GUARDIAN, null, null, true)
        );

        assertEquals(InvitationErrorCode.GUARDIAN_LIMIT_REACHED, exception.getErrorCode());
    }

    @Test
    void 보호자_요청_생성은_약속_확인값이_true가_아니면_막는다() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.createInvitation(INVITER_ID, InvitationType.GUARDIAN, null, null, false)
        );

        assertEquals(CommonErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void 발신자나_수신자의_보호자_인원이_가득_차면_수락하지_않고_대기상태를_유지한다() {
        for (Long fullUserId : new Long[]{INVITER_ID, INVITEE_ID}) {
            Invitation guardianInvitation = guardianInvitation();
            when(invitationRepository.findByTokenHashForUpdate(tokenGenerator.hash(TOKEN)))
                    .thenReturn(Optional.of(guardianInvitation));
            when(relationshipRepository.countBySourceUserIdAndRelationshipTypeAndStatus(
                    anyLong(), org.mockito.ArgumentMatchers.eq(RelationshipType.GUARDIAN),
                    org.mockito.ArgumentMatchers.eq(RelationshipStatus.ACTIVE)))
                    .thenAnswer(call -> fullUserId.equals(call.getArgument(0)) ? 3L : 0L);

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> service.acceptInvitation(TOKEN, INVITEE_ID)
            );

            assertEquals(InvitationErrorCode.GUARDIAN_LIMIT_REACHED, exception.getErrorCode());
            assertEquals(InvitationStatus.PENDING, guardianInvitation.getStatus());
        }
    }

    @Test
    void 수락하면_응답자와_발신자에서_수신자_방향의_관계를_한_번_저장한다() {
        when(relationshipRepository.existsBySourceUserIdAndTargetUserIdAndRelationshipTypeAndStatus(
                INVITER_ID, INVITEE_ID, RelationshipType.MY_PERSON, RelationshipStatus.ACTIVE)).thenReturn(false);

        service.acceptInvitation(TOKEN, INVITEE_ID);

        assertEquals(InvitationStatus.ACCEPTED, invitation.getStatus());
        assertEquals(INVITEE_ID, invitation.getInviteeId());
        verify(relationshipRepository).save(any(Relationship.class));

        BusinessException duplicate = assertThrows(
                BusinessException.class,
                () -> service.acceptInvitation(TOKEN, INVITEE_ID)
        );
        assertEquals(InvitationErrorCode.INVITATION_NOT_PENDING, duplicate.getErrorCode());
        verify(relationshipRepository).save(any(Relationship.class));
    }

    @Test
    void 초대자의_개인_라벨은_수락_후_초대자_방향_관계에_적용한다() {
        invitation = Invitation.create(
                INVITER_ID,
                InvitationType.MY_PERSON,
                tokenGenerator.hash(TOKEN),
                null,
                "친구",
                NOW.plusMinutes(10)
        );
        when(invitationRepository.findByTokenHashForUpdate(tokenGenerator.hash(TOKEN)))
                .thenReturn(Optional.of(invitation));
        when(relationshipRepository.existsBySourceUserIdAndTargetUserIdAndRelationshipTypeAndStatus(
                INVITER_ID, INVITEE_ID, RelationshipType.MY_PERSON, RelationshipStatus.ACTIVE)).thenReturn(false);

        service.acceptInvitation(TOKEN, INVITEE_ID);

        ArgumentCaptor<Relationship> relationshipCaptor = ArgumentCaptor.forClass(Relationship.class);
        verify(relationshipRepository).save(relationshipCaptor.capture());
        assertEquals(INVITER_ID, relationshipCaptor.getValue().getSourceUserId());
        assertEquals(INVITEE_ID, relationshipCaptor.getValue().getTargetUserId());
        assertEquals("친구", relationshipCaptor.getValue().getPersonalLabel());
    }

    @Test
    void 이미_보호자_관계인_사용자들의_오래된_나의_사람_초대는_수락할_수_없다() {
        when(relationshipRepository.findActiveBetweenUsers(INVITER_ID, INVITEE_ID, RelationshipStatus.ACTIVE))
                .thenReturn(java.util.List.of(
                        Relationship.createGuardian(INVITER_ID, INVITEE_ID, 1),
                        Relationship.createGuardian(INVITEE_ID, INVITER_ID, 1)
                ));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.acceptInvitation(TOKEN, INVITEE_ID)
        );

        assertEquals(InvitationErrorCode.GUARDIAN_RELATIONSHIP_ALREADY_EXISTS, exception.getErrorCode());
        assertEquals(InvitationStatus.PENDING, invitation.getStatus());
        verify(relationshipRepository, never()).save(any());
    }

    @Test
    void 보호자_전환은_양방향_비상연락을_제거하고_각_사용자의_순서를_정규화한다() {
        invitation = guardianInvitation();
        when(invitationRepository.findByTokenHashForUpdate(tokenGenerator.hash(TOKEN)))
                .thenReturn(Optional.of(invitation));
        Relationship inviterToInvitee = Relationship.createMyPerson(INVITER_ID, INVITEE_ID, "친구");
        Relationship inviteeToInviter = Relationship.createMyPerson(INVITEE_ID, INVITER_ID, null);
        Relationship inviterGuardianFirst = Relationship.createGuardian(INVITER_ID, 5L, 1);
        Relationship inviterGuardianGap = Relationship.createGuardian(INVITER_ID, 6L, 3);
        Relationship inviteeGuardianFirst = Relationship.createGuardian(INVITEE_ID, 7L, 1);
        Relationship inviteeGuardianGap = Relationship.createGuardian(INVITEE_ID, 8L, 3);
        when(relationshipRepository.findActiveBetweenUsers(INVITER_ID, INVITEE_ID, RelationshipStatus.ACTIVE))
                .thenReturn(java.util.List.of(inviterToInvitee, inviteeToInviter));
        when(relationshipRepository.findAllBySourceUserIdAndRelationshipTypeAndStatusOrderByGuardianPriorityAsc(
                INVITER_ID, RelationshipType.GUARDIAN, RelationshipStatus.ACTIVE))
                .thenReturn(java.util.List.of(inviterGuardianFirst, inviterGuardianGap));
        when(relationshipRepository.findAllBySourceUserIdAndRelationshipTypeAndStatusOrderByGuardianPriorityAsc(
                INVITEE_ID, RelationshipType.GUARDIAN, RelationshipStatus.ACTIVE))
                .thenReturn(java.util.List.of(inviteeGuardianFirst, inviteeGuardianGap));
        EmergencyContact inviterPair = EmergencyContact.create(INVITER_ID, INVITEE_ID, 1);
        EmergencyContact inviterOther = EmergencyContact.create(INVITER_ID, 3L, 2);
        EmergencyContact inviteePair = EmergencyContact.create(INVITEE_ID, INVITER_ID, 1);
        EmergencyContact inviteeOther = EmergencyContact.create(INVITEE_ID, 4L, 2);
        when(emergencyContactRepository.findAllByUserIdOrderByPriorityAsc(INVITER_ID))
                .thenReturn(java.util.List.of(inviterPair, inviterOther));
        when(emergencyContactRepository.findAllByUserIdOrderByPriorityAsc(INVITEE_ID))
                .thenReturn(java.util.List.of(inviteePair, inviteeOther));

        service.acceptInvitation(TOKEN, INVITEE_ID);

        assertEquals(RelationshipStatus.ENDED, inviterToInvitee.getStatus());
        assertEquals(RelationshipStatus.ENDED, inviteeToInviter.getStatus());
        assertEquals(1, inviterGuardianFirst.getGuardianPriority());
        assertEquals(2, inviterGuardianGap.getGuardianPriority());
        assertEquals(1, inviteeGuardianFirst.getGuardianPriority());
        assertEquals(2, inviteeGuardianGap.getGuardianPriority());
        assertEquals(1, inviterOther.getPriority());
        assertEquals(1, inviteeOther.getPriority());
        verify(emergencyContactRepository).deleteAll(java.util.List.of(inviterPair));
        verify(emergencyContactRepository).deleteAll(java.util.List.of(inviteePair));
        ArgumentCaptor<java.util.List<Relationship>> guardianCaptor = ArgumentCaptor.forClass(java.util.List.class);
        verify(relationshipRepository, org.mockito.Mockito.atLeastOnce()).saveAllAndFlush(guardianCaptor.capture());
        java.util.List<Relationship> newGuardians = guardianCaptor.getAllValues().getLast();
        assertEquals(2, newGuardians.size());
        assertTrue(newGuardians.stream().allMatch(guardian -> guardian.getGuardianPriority() == 3));
        assertTrue(newGuardians.stream().allMatch(guardian -> guardian.getRelationshipType() == RelationshipType.GUARDIAN));
        assertTrue(newGuardians.stream().anyMatch(guardian -> guardian.getSourceUserId().equals(INVITER_ID)
                && guardian.getTargetUserId().equals(INVITEE_ID)));
        assertTrue(newGuardians.stream().anyMatch(guardian -> guardian.getSourceUserId().equals(INVITEE_ID)
                && guardian.getTargetUserId().equals(INVITER_ID)));
    }

    @Test
    void 이미_서로_보호자면_보호자_초대_수락도_거부한다() {
        invitation = guardianInvitation();
        when(invitationRepository.findByTokenHashForUpdate(tokenGenerator.hash(TOKEN)))
                .thenReturn(Optional.of(invitation));
        when(relationshipRepository.findActiveBetweenUsers(INVITER_ID, INVITEE_ID, RelationshipStatus.ACTIVE))
                .thenReturn(java.util.List.of(
                        Relationship.createGuardian(INVITER_ID, INVITEE_ID, 1),
                        Relationship.createGuardian(INVITEE_ID, INVITER_ID, 1)
                ));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.acceptInvitation(TOKEN, INVITEE_ID)
        );

        assertEquals(InvitationErrorCode.GUARDIAN_RELATIONSHIP_ALREADY_EXISTS, exception.getErrorCode());
        assertEquals(InvitationStatus.PENDING, invitation.getStatus());
        verify(relationshipRepository, never()).saveAllAndFlush(any());
    }

    @Test
    void 사용자_잠금_대기_중_만료된_초대는_409_오류로_막는다() {
        clock.setInstant(Instant.parse("2026-11-09T02:59:59Z")); // Asia/Seoul 11:59:59
        invitation = invitation(InvitationStatus.PENDING, NOW);
        when(invitationRepository.findByTokenHashForUpdate(tokenGenerator.hash(TOKEN))).thenReturn(Optional.of(invitation));
        when(userRepository.findByIdForUpdate(INVITEE_ID)).thenAnswer(invocation -> {
            clock.setInstant(Instant.parse("2026-11-09T03:00:00Z")); // 잠금 대기 뒤 만료 시각 도달
            return Optional.of(invitee);
        });

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.acceptInvitation(TOKEN, INVITEE_ID)
        );

        assertEquals(InvitationErrorCode.INVITATION_EXPIRED, exception.getErrorCode());
        assertEquals(InvitationStatus.PENDING, invitation.getStatus());
        verify(relationshipRepository, never()).save(any());
    }

    @Test
    void 발신자_본인은_자기_초대에_응답할_수_없다() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.rejectInvitation(TOKEN, INVITER_ID)
        );

        assertEquals(InvitationErrorCode.INVITATION_SELF_RESPONSE, exception.getErrorCode());
        verify(userRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void 반대_방향_추가는_초대를_수락한_사용자만_가능하다() {
        invitation = invitation(InvitationStatus.ACCEPTED, NOW.minusDays(1));
        ReflectionTestUtils.setField(invitation, "inviteeId", INVITEE_ID);
        when(invitationRepository.findByTokenHashForUpdate(tokenGenerator.hash(TOKEN))).thenReturn(Optional.of(invitation));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.addReciprocalRelationship(TOKEN, INVITER_ID)
        );

        assertEquals(InvitationErrorCode.INVITATION_RESPONDER_ONLY, exception.getErrorCode());
        verify(relationshipRepository, never()).save(any());
    }

    @Test
    void 원래_발신자_관계가_해제되었으면_반대_방향_추가를_막는다() {
        invitation = invitation(InvitationStatus.ACCEPTED, NOW.minusDays(1));
        ReflectionTestUtils.setField(invitation, "inviteeId", INVITEE_ID);
        when(invitationRepository.findByTokenHashForUpdate(tokenGenerator.hash(TOKEN))).thenReturn(Optional.of(invitation));
        when(relationshipRepository.existsBySourceUserIdAndTargetUserIdAndRelationshipTypeAndStatus(
                INVITER_ID, INVITEE_ID, RelationshipType.MY_PERSON, RelationshipStatus.ACTIVE)).thenReturn(false);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.addReciprocalRelationship(TOKEN, INVITEE_ID)
        );

        assertEquals(InvitationErrorCode.INVITATION_NOT_ACCEPTED, exception.getErrorCode());
        verify(relationshipRepository, never()).save(any());
    }

    @Test
    void 반대_방향_추가에는_유효한_발신자_방향_관계가_필요하다() {
        invitation = invitation(InvitationStatus.ACCEPTED, NOW.minusDays(1));
        ReflectionTestUtils.setField(invitation, "inviteeId", INVITEE_ID);
        when(invitationRepository.findByTokenHashForUpdate(tokenGenerator.hash(TOKEN))).thenReturn(Optional.of(invitation));
        when(relationshipRepository.existsBySourceUserIdAndTargetUserIdAndRelationshipTypeAndStatus(
                INVITER_ID, INVITEE_ID, RelationshipType.MY_PERSON, RelationshipStatus.ACTIVE)).thenReturn(true);
        when(relationshipRepository.existsBySourceUserIdAndTargetUserIdAndRelationshipTypeAndStatus(
                INVITEE_ID, INVITER_ID, RelationshipType.MY_PERSON, RelationshipStatus.ACTIVE)).thenReturn(false);

        service.addReciprocalRelationship(TOKEN, INVITEE_ID);

        verify(relationshipRepository).save(any(Relationship.class));
        assertTrue(invitation.getStatus() == InvitationStatus.ACCEPTED);
    }

    private Invitation invitation(InvitationStatus status, LocalDateTime expiresAt) {
        Invitation result = Invitation.create(
                INVITER_ID,
                InvitationType.MY_PERSON,
                tokenGenerator.hash(TOKEN),
                null,
                expiresAt
        );
        if (status == InvitationStatus.ACCEPTED || status == InvitationStatus.REJECTED) {
            result.respond(INVITEE_ID, status, expiresAt.minusSeconds(1));
        } else if (status == InvitationStatus.CANCELED) {
            result.cancel();
        } else if (status == InvitationStatus.EXPIRED) {
            result.expire();
        }
        return result;
    }

    private Invitation guardianInvitation() {
        return Invitation.create(
                INVITER_ID,
                InvitationType.GUARDIAN,
                tokenGenerator.hash(TOKEN),
                "필요할 때 연락을 확인해요",
                null,
                NOW.plusMinutes(10)
        );
    }

    private User user(Long id, String name) {
        User result = User.register(name + "@example.com", "hash", name);
        ReflectionTestUtils.setField(result, "id", id);
        return result;
    }

    private static class AdjustableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        private AdjustableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new AdjustableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
