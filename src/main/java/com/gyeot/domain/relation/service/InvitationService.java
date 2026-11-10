package com.gyeot.domain.relation.service;

import com.gyeot.domain.relation.dto.response.InvitationCreateResponse;
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
import com.gyeot.domain.user.entity.AccountStatus;
import com.gyeot.domain.user.entity.User;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
public class InvitationService {

    private static final long INVITATION_VALID_DAYS = 7;

    private final InvitationRepository invitationRepository;
    private final RelationshipRepository relationshipRepository;
    private final UserRepository userRepository;
    private final InvitationTokenGenerator tokenGenerator;
    private final Clock serviceClock;
    private final String invitationBaseUrl;

    public InvitationService(
            InvitationRepository invitationRepository,
            RelationshipRepository relationshipRepository,
            UserRepository userRepository,
            InvitationTokenGenerator tokenGenerator,
            Clock serviceClock,
            @Value("${gyeot.invitation.base-url}") String invitationBaseUrl
    ) {
        this.invitationRepository = invitationRepository;
        this.relationshipRepository = relationshipRepository;
        this.userRepository = userRepository;
        this.tokenGenerator = tokenGenerator;
        this.serviceClock = serviceClock;
        this.invitationBaseUrl = removeTrailingSlash(invitationBaseUrl);
    }

    /** 새 초대를 만들고, 원문 토큰은 링크를 구성해 응답하는 데만 사용합니다. */
    @Transactional
    public InvitationCreateResponse createInvitation(Long inviterId, InvitationType invitationType) {
        if (invitationType != InvitationType.MY_PERSON) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }

        InvitationTokenGenerator.IssuedInvitationToken issuedToken = tokenGenerator.issue();
        LocalDateTime now = LocalDateTime.now(serviceClock);
        LocalDateTime expiresAt = now.plusDays(INVITATION_VALID_DAYS);

        Invitation invitation = Invitation.create(
                inviterId,
                InvitationType.MY_PERSON,
                issuedToken.tokenHash(),
                null,
                expiresAt
        );
        Invitation savedInvitation = invitationRepository.save(invitation);

        String invitationUrl = invitationBaseUrl + "/" + issuedToken.token();
        return new InvitationCreateResponse(savedInvitation.getId(), invitationUrl, expiresAt);
    }

    /** 발신자 본인의 대기 중 초대만 취소합니다. 다시 초대하면 새 토큰으로 별도 행을 생성합니다. */
    @Transactional
    public void cancelInvitation(Long inviterId, Long invitationId) {
        Invitation invitation = invitationRepository.findByIdAndInviterIdForUpdate(invitationId, inviterId)
                .orElseThrow(() -> new BusinessException(InvitationErrorCode.INVITATION_NOT_FOUND));

        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new BusinessException(InvitationErrorCode.INVITATION_NOT_PENDING);
        }
        if (!LocalDateTime.now(serviceClock).isBefore(invitation.getExpiresAt())) {
            throw new BusinessException(InvitationErrorCode.INVITATION_EXPIRED);
        }

        invitation.cancel();
    }

    /** 초대 링크로 들어온 화면에 발신자와 현재 처리 가능 여부를 돌려줍니다. */
    @Transactional
    public InvitationDetailsResponse getInvitation(String token) {
        Invitation invitation = findByToken(token, false);
        if (invitation.getInvitationType() != InvitationType.MY_PERSON) {
            throw new BusinessException(InvitationErrorCode.INVITATION_NOT_FOUND);
        }
        User inviter = userRepository.findById(invitation.getInviterId())
                .orElseThrow(() -> new BusinessException(InvitationErrorCode.INVITATION_NOT_FOUND));
        LocalDateTime now = LocalDateTime.now(serviceClock);
        boolean expired = !now.isBefore(invitation.getExpiresAt());
        if (expired && invitation.getStatus() == InvitationStatus.PENDING) {
            invitation.expire();
        }

        return new InvitationDetailsResponse(
                invitation.getId(),
                invitation.getInvitationType(),
                inviter.getName(),
                invitation.getStatus(),
                invitation.getExpiresAt(),
                invitation.getStatus() == InvitationStatus.PENDING
        );
    }

    /** 초대를 수락하고 발신자 → 수락자 방향의 나의 사람 관계를 만듭니다. */
    @Transactional
    public void acceptInvitation(String token, Long inviteeId) {
        Invitation invitation = findByToken(token, true);
        validateCanRespond(invitation, inviteeId);
        lockActiveUsers(invitation.getInviterId(), inviteeId);

        if (relationshipRepository.existsBySourceUserIdAndTargetUserIdAndRelationshipTypeAndStatus(
                invitation.getInviterId(), inviteeId, RelationshipType.MY_PERSON, RelationshipStatus.ACTIVE)) {
            throw new BusinessException(InvitationErrorCode.RELATIONSHIP_ALREADY_EXISTS);
        }

        invitation.respond(inviteeId, InvitationStatus.ACCEPTED, LocalDateTime.now(serviceClock));
        relationshipRepository.save(Relationship.createMyPerson(invitation.getInviterId(), inviteeId, null));
    }

    /** 초대를 거절하고 응답 사용자를 초대에 기록합니다. */
    @Transactional
    public void rejectInvitation(String token, Long inviteeId) {
        Invitation invitation = findByToken(token, true);
        validateCanRespond(invitation, inviteeId);
        lockActiveUsers(invitation.getInviterId(), inviteeId);
        invitation.respond(inviteeId, InvitationStatus.REJECTED, LocalDateTime.now(serviceClock));
    }

    /** 수락자가 원하면 별도 호출로 반대 방향의 나의 사람 관계를 추가합니다. */
    @Transactional
    public void addReciprocalRelationship(String token, Long userId) {
        Invitation invitation = findByToken(token, true);
        if (userId == null) {
            throw new BusinessException(InvitationErrorCode.INVITATION_RESPONDER_ONLY);
        }
        if (invitation.getStatus() != InvitationStatus.ACCEPTED) {
            throw new BusinessException(InvitationErrorCode.INVITATION_NOT_ACCEPTED);
        }
        if (!userId.equals(invitation.getInviteeId())) {
            throw new BusinessException(InvitationErrorCode.INVITATION_RESPONDER_ONLY);
        }

        lockActiveUsers(userId, invitation.getInviterId());
        if (relationshipRepository.existsBySourceUserIdAndTargetUserIdAndRelationshipTypeAndStatus(
                userId, invitation.getInviterId(), RelationshipType.MY_PERSON, RelationshipStatus.ACTIVE)) {
            throw new BusinessException(InvitationErrorCode.RELATIONSHIP_ALREADY_EXISTS);
        }
        relationshipRepository.save(Relationship.createMyPerson(userId, invitation.getInviterId(), null));
    }

    private Invitation findByToken(String token, boolean lock) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(InvitationErrorCode.INVITATION_NOT_FOUND);
        }
        String tokenHash = tokenGenerator.hash(token);
        return (lock
                ? invitationRepository.findByTokenHashForUpdate(tokenHash)
                : invitationRepository.findByTokenHash(tokenHash))
                .orElseThrow(() -> new BusinessException(InvitationErrorCode.INVITATION_NOT_FOUND));
    }

    private void validateCanRespond(Invitation invitation, Long inviteeId) {
        if (inviteeId == null) {
            throw new BusinessException(InvitationErrorCode.INVITATION_USER_NOT_AVAILABLE);
        }
        if (invitation.getInvitationType() != InvitationType.MY_PERSON) {
            throw new BusinessException(InvitationErrorCode.INVITATION_NOT_FOUND);
        }
        if (invitation.getInviterId().equals(inviteeId)) {
            throw new BusinessException(InvitationErrorCode.INVITATION_SELF_RESPONSE);
        }
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new BusinessException(InvitationErrorCode.INVITATION_NOT_PENDING);
        }
        if (!LocalDateTime.now(serviceClock).isBefore(invitation.getExpiresAt())) {
            throw new BusinessException(InvitationErrorCode.INVITATION_EXPIRED);
        }
    }

    // 두 사용자를 ID 순서대로 잠가 서로 반대 방향 초대의 동시 관계 생성을 직렬화합니다.
    private void lockActiveUsers(Long firstUserId, Long secondUserId) {
        Long lowerId = Math.min(firstUserId, secondUserId);
        Long higherId = Math.max(firstUserId, secondUserId);
        User lower = userRepository.findByIdForUpdate(lowerId)
                .orElseThrow(() -> new BusinessException(InvitationErrorCode.INVITATION_USER_NOT_AVAILABLE));
        User higher = userRepository.findByIdForUpdate(higherId)
                .orElseThrow(() -> new BusinessException(InvitationErrorCode.INVITATION_USER_NOT_AVAILABLE));
        if (lower.getAccountStatus() != AccountStatus.ACTIVE || higher.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException(InvitationErrorCode.INVITATION_USER_NOT_AVAILABLE);
        }
    }

    private String removeTrailingSlash(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("초대 링크 기본 주소(gyeot.invitation.base-url)가 필요합니다.");
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
