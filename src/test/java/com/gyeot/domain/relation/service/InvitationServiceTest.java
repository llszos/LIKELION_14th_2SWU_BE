package com.gyeot.domain.relation.service;

import com.gyeot.domain.relation.dto.response.InvitationCreateResponse;
import com.gyeot.domain.relation.entity.Invitation;
import com.gyeot.domain.relation.entity.InvitationStatus;
import com.gyeot.domain.relation.entity.InvitationType;
import com.gyeot.domain.relation.exception.InvitationErrorCode;
import com.gyeot.domain.relation.repository.EmergencyContactRepository;
import com.gyeot.domain.relation.repository.InvitationRepository;
import com.gyeot.domain.relation.repository.RelationshipRepository;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvitationServiceTest {

    private static final Long INVITER_ID = 11L;
    private static final Long INVITATION_ID = 51L;

    private final List<Invitation> savedInvitations = new ArrayList<>();
    private Invitation cancellationTarget;
    private InvitationService invitationService;
    private InvitationTokenGenerator tokenGenerator;

    @BeforeEach
    void setUp() {
        savedInvitations.clear();
        cancellationTarget = null;
        tokenGenerator = new InvitationTokenGenerator();

        InvitationRepository repository = (InvitationRepository) Proxy.newProxyInstance(
                InvitationRepository.class.getClassLoader(),
                new Class<?>[]{InvitationRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "save" -> {
                        Invitation invitation = (Invitation) args[0];
                        ReflectionTestUtils.setField(invitation, "id", (long) savedInvitations.size() + 1);
                        savedInvitations.add(invitation);
                        yield invitation;
                    }
                    case "findByIdAndInviterIdForUpdate" -> {
                        Long id = (Long) args[0];
                        Long inviterId = (Long) args[1];
                        if (cancellationTarget != null
                                && INVITATION_ID.equals(id)
                                && INVITER_ID.equals(inviterId)) {
                            yield Optional.of(cancellationTarget);
                        }
                        yield Optional.empty();
                    }
                    case "toString" -> "InvitationRepository test proxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );

        Clock clock = Clock.fixed(Instant.parse("2026-11-09T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        invitationService = new InvitationService(
                repository,
                org.mockito.Mockito.mock(RelationshipRepository.class),
                org.mockito.Mockito.mock(EmergencyContactRepository.class),
                org.mockito.Mockito.mock(UserRepository.class),
                tokenGenerator,
                clock,
                "https://example.com/invitations"
        );
    }

    @Test
    void 나의_사람_초대는_7일_유효하고_원문_토큰은_저장하지_않는다() {
        InvitationCreateResponse response = invitationService.createInvitation(INVITER_ID, InvitationType.MY_PERSON);
        Invitation saved = savedInvitations.get(0);

        assertEquals(1L, response.invitationId());
        assertEquals(InvitationType.MY_PERSON, saved.getInvitationType());
        assertEquals(InvitationStatus.PENDING, saved.getStatus());
        assertEquals(INVITER_ID, saved.getInviterId());
        assertEquals(response.expiresAt(), saved.getExpiresAt());
        assertEquals(Instant.parse("2026-11-16T03:00:00Z").atZone(ZoneId.of("Asia/Seoul")).toLocalDateTime(),
                response.expiresAt());
        assertNotNull(response.invitationUrl());
        assertTrue(response.invitationUrl().startsWith("https://example.com/invitations/"));
        assertNotEquals(response.invitationUrl(), saved.getTokenHash());
        String rawToken = response.invitationUrl().substring("https://example.com/invitations/".length());
        assertEquals(tokenGenerator.hash(rawToken), saved.getTokenHash());
    }

    @Test
    void 다시_초대하면_서로_다른_새_토큰을_발급한다() {
        InvitationCreateResponse first = invitationService.createInvitation(INVITER_ID, InvitationType.MY_PERSON);
        InvitationCreateResponse second = invitationService.createInvitation(INVITER_ID, InvitationType.MY_PERSON);

        assertNotEquals(first.invitationUrl(), second.invitationUrl());
        assertEquals(2, savedInvitations.size());
        assertNotEquals(savedInvitations.get(0).getTokenHash(), savedInvitations.get(1).getTokenHash());
    }

    @Test
    void 발신자는_본인의_대기_중_초대를_취소할_수_있다() {
        cancellationTarget = Invitation.create(
                INVITER_ID,
                InvitationType.MY_PERSON,
                "token-hash",
                null,
                java.time.LocalDateTime.of(2026, 11, 16, 12, 0)
        );

        invitationService.cancelInvitation(INVITER_ID, INVITATION_ID);

        assertEquals(InvitationStatus.CANCELED, cancellationTarget.getStatus());
    }

    @Test
    void 다른_사람의_초대는_찾을_수_없다() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> invitationService.cancelInvitation(INVITER_ID, INVITATION_ID)
        );

        assertEquals(InvitationErrorCode.INVITATION_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void 취소되었거나_응답이_끝난_초대는_다시_취소할_수_없다() {
        cancellationTarget = Invitation.create(
                INVITER_ID,
                InvitationType.MY_PERSON,
                "token-hash",
                null,
                java.time.LocalDateTime.of(2026, 11, 16, 12, 0)
        );
        cancellationTarget.cancel();

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> invitationService.cancelInvitation(INVITER_ID, INVITATION_ID)
        );

        assertEquals(InvitationErrorCode.INVITATION_NOT_PENDING, exception.getErrorCode());
    }
}
