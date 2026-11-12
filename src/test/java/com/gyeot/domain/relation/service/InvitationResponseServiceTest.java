package com.gyeot.domain.relation.service;

import com.gyeot.domain.relation.dto.response.InvitationDetailsResponse;
import com.gyeot.domain.relation.entity.Invitation;
import com.gyeot.domain.relation.entity.InvitationStatus;
import com.gyeot.domain.relation.entity.InvitationType;
import com.gyeot.domain.relation.entity.Relationship;
import com.gyeot.domain.relation.entity.RelationshipStatus;
import com.gyeot.domain.relation.entity.RelationshipType;
import com.gyeot.domain.relation.exception.InvitationErrorCode;
import com.gyeot.domain.relation.repository.InvitationRepository;
import com.gyeot.domain.relation.repository.RelationshipRepository;
import com.gyeot.domain.user.entity.User;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
        userRepository = mock(UserRepository.class);
        clock = new AdjustableClock(Instant.parse("2026-11-09T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        tokenGenerator = new InvitationTokenGenerator();
        service = new InvitationService(
                invitationRepository,
                relationshipRepository,
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
        assertFalse(response.canRespond());
        assertEquals(InvitationStatus.PENDING, invitation.getStatus());
        verify(invitationRepository, never()).save(any());
    }

    @Test
    void 만료된_조회에서도_이미_수락된_상태는_유지한다() {
        invitation = invitation(InvitationStatus.ACCEPTED, NOW.minusSeconds(1));
        when(invitationRepository.findByTokenHash(tokenGenerator.hash(TOKEN))).thenReturn(Optional.of(invitation));

        InvitationDetailsResponse response = service.getInvitation(TOKEN);

        assertEquals(InvitationStatus.ACCEPTED, response.status());
        assertFalse(response.canRespond());
        assertEquals(InvitationStatus.ACCEPTED, invitation.getStatus());
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
