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
import java.util.ArrayList;
import java.util.List;

@Service
public class InvitationService {

    private static final long INVITATION_VALID_DAYS = 7;
    private static final long MAX_ACTIVE_GUARDIANS = 3;
    private static final String INVITATION_DEFAULT_MESSAGE = "함께 해요";
    private static final String AUTHENTICATION_REQUIRED = "AUTHENTICATION_REQUIRED";
    private static final String SELF_INVITATION = "SELF_INVITATION";

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
        return createInvitation(inviterId, invitationType, null, null, null);
    }

    /** 초대 유형별 입력을 확인하고 링크를 발급합니다. */
    @Transactional
    public InvitationCreateResponse createInvitation(
            Long inviterId,
            InvitationType invitationType,
            String personalLabel,
            String message,
            Boolean promiseConfirmed
    ) {
        validateCreateRequest(invitationType, personalLabel, message, promiseConfirmed);

        if (invitationType == InvitationType.GUARDIAN) {
            User inviter = lockActiveUser(inviterId);
            if (countActiveGuardians(inviter.getId()) >= MAX_ACTIVE_GUARDIANS) {
                throw new BusinessException(InvitationErrorCode.GUARDIAN_LIMIT_REACHED);
            }
        }

        InvitationTokenGenerator.IssuedInvitationToken issuedToken = tokenGenerator.issue();
        LocalDateTime now = LocalDateTime.now(serviceClock);
        LocalDateTime expiresAt = now.plusDays(INVITATION_VALID_DAYS);

        Invitation invitation = Invitation.create(
                inviterId,
                invitationType,
                issuedToken.tokenHash(),
                message,
                personalLabel,
                expiresAt
        );
        Invitation savedInvitation = invitationRepository.save(invitation);

        String invitationUrl = invitationBaseUrl + "/" + issuedToken.token();
        return new InvitationCreateResponse(savedInvitation.getId(), invitationUrl, expiresAt);
    }

    private void validateCreateRequest(
            InvitationType invitationType,
            String personalLabel,
            String message,
            Boolean promiseConfirmed
    ) {
        if (invitationType == null) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        if (invitationType == InvitationType.MY_PERSON) {
            if (message != null || promiseConfirmed != null) {
                throw new BusinessException(CommonErrorCode.INVALID_INPUT);
            }
            if (personalLabel != null && (personalLabel.isBlank() || personalLabel.length() > 50)) {
                throw new BusinessException(CommonErrorCode.INVALID_INPUT);
            }
            return;
        }
        if (personalLabel != null || !Boolean.TRUE.equals(promiseConfirmed)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
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
    @Transactional(readOnly = true)
    public InvitationDetailsResponse getInvitation(String token) {
        return getInvitation(token, null);
    }

    /** 보호자 링크는 로그인한 수신자에게 현재 인원 제한도 함께 알려줍니다. */
    @Transactional(readOnly = true)
    public InvitationDetailsResponse getInvitation(String token, Long viewerId) {
        Invitation invitation = findByToken(token, false);
        User inviter = userRepository.findById(invitation.getInviterId())
                .orElseThrow(() -> new BusinessException(InvitationErrorCode.INVITATION_NOT_FOUND));
        LocalDateTime now = LocalDateTime.now(serviceClock);
        boolean expired = !now.isBefore(invitation.getExpiresAt());
        InvitationStatus responseStatus = expired && invitation.getStatus() == InvitationStatus.PENDING
                ? InvitationStatus.EXPIRED
                : invitation.getStatus();

        boolean guardianLimitReached = invitation.getInvitationType() == InvitationType.GUARDIAN
                && viewerId != null
                && countActiveGuardians(viewerId) >= MAX_ACTIVE_GUARDIANS;
        boolean canAccept = false;
        String unavailableReason = null;
        if (responseStatus == InvitationStatus.PENDING) {
            if (viewerId == null) {
                unavailableReason = AUTHENTICATION_REQUIRED;
            } else if (viewerId.equals(invitation.getInviterId())) {
                unavailableReason = SELF_INVITATION;
            } else if (guardianLimitReached) {
                unavailableReason = InvitationErrorCode.GUARDIAN_LIMIT_REACHED.getCode();
            } else {
                canAccept = true;
            }
        }

        return new InvitationDetailsResponse(
                invitation.getInvitationType(),
                new InvitationDetailsResponse.Inviter(inviter.getId(), inviter.getName()),
                INVITATION_DEFAULT_MESSAGE,
                invitation.getInvitationType() == InvitationType.GUARDIAN ? invitation.getMessage() : null,
                invitation.getExpiresAt(),
                responseStatus,
                canAccept,
                unavailableReason
        );
    }

    /** 초대를 수락하고, 보호자 요청은 수락 직전 양쪽의 인원 제한을 다시 확인합니다. */
    @Transactional
    public void acceptInvitation(String token, Long inviteeId) {
        Invitation invitation = findByToken(token, true);
        validateCanRespond(invitation, inviteeId);
        lockActiveUsers(invitation.getInviterId(), inviteeId);
        LocalDateTime respondedAt = LocalDateTime.now(serviceClock);
        validateNotExpired(invitation, respondedAt);

        if (invitation.getInvitationType() == InvitationType.MY_PERSON) {
            if (relationshipRepository.existsBySourceUserIdAndTargetUserIdAndRelationshipTypeAndStatus(
                    invitation.getInviterId(), inviteeId, RelationshipType.MY_PERSON, RelationshipStatus.ACTIVE)) {
                throw new BusinessException(InvitationErrorCode.RELATIONSHIP_ALREADY_EXISTS);
            }
            invitation.respond(inviteeId, InvitationStatus.ACCEPTED, respondedAt);
            relationshipRepository.save(Relationship.createMyPerson(
                    invitation.getInviterId(), inviteeId, invitation.getPersonalLabel()));
            return;
        }

        validateGuardianCapacity(invitation.getInviterId(), inviteeId);
        registerMutualGuardians(invitation.getInviterId(), inviteeId, respondedAt);
        invitation.respond(inviteeId, InvitationStatus.ACCEPTED, respondedAt);
    }

    /** 초대를 거절하고 응답 사용자를 초대에 기록합니다. */
    @Transactional
    public void rejectInvitation(String token, Long inviteeId) {
        Invitation invitation = findByToken(token, true);
        validateCanRespond(invitation, inviteeId);
        lockActiveUsers(invitation.getInviterId(), inviteeId);
        LocalDateTime respondedAt = LocalDateTime.now(serviceClock);
        validateNotExpired(invitation, respondedAt);
        invitation.respond(inviteeId, InvitationStatus.REJECTED, respondedAt);
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
        if (!relationshipRepository.existsBySourceUserIdAndTargetUserIdAndRelationshipTypeAndStatus(
                invitation.getInviterId(), userId, RelationshipType.MY_PERSON, RelationshipStatus.ACTIVE)) {
            throw new BusinessException(InvitationErrorCode.INVITATION_NOT_ACCEPTED);
        }
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
        if (invitation.getInviterId().equals(inviteeId)) {
            throw new BusinessException(InvitationErrorCode.INVITATION_SELF_RESPONSE);
        }
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new BusinessException(InvitationErrorCode.INVITATION_NOT_PENDING);
        }
    }

    private void validateNotExpired(Invitation invitation, LocalDateTime respondedAt) {
        if (!respondedAt.isBefore(invitation.getExpiresAt())) {
            throw new BusinessException(InvitationErrorCode.INVITATION_EXPIRED);
        }
    }

    // 두 사용자를 ID 순서대로 잠가 서로 반대 방향 초대의 동시 관계 생성을 직렬화합니다.
    private void lockActiveUsers(Long firstUserId, Long secondUserId) {
        Long lowerId = Math.min(firstUserId, secondUserId);
        Long higherId = Math.max(firstUserId, secondUserId);
        User lower = userRepository.findByIdForUpdate(lowerId)
                .orElseThrow(() -> new BusinessException(InvitationErrorCode.INVITATION_USER_NOT_AVAILABLE));
        User higher = lowerId.equals(higherId)
                ? lower
                : userRepository.findByIdForUpdate(higherId)
                        .orElseThrow(() -> new BusinessException(InvitationErrorCode.INVITATION_USER_NOT_AVAILABLE));
        if (lower.getAccountStatus() != AccountStatus.ACTIVE || higher.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException(InvitationErrorCode.INVITATION_USER_NOT_AVAILABLE);
        }
    }

    private User lockActiveUser(Long userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(InvitationErrorCode.INVITATION_USER_NOT_AVAILABLE));
        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException(InvitationErrorCode.INVITATION_USER_NOT_AVAILABLE);
        }
        return user;
    }

    private long countActiveGuardians(Long userId) {
        return relationshipRepository.countBySourceUserIdAndRelationshipTypeAndStatus(
                userId, RelationshipType.GUARDIAN, RelationshipStatus.ACTIVE);
    }

    private void validateGuardianCapacity(Long inviterId, Long inviteeId) {
        if (countActiveGuardians(inviterId) >= MAX_ACTIVE_GUARDIANS
                || countActiveGuardians(inviteeId) >= MAX_ACTIVE_GUARDIANS) {
            throw new BusinessException(InvitationErrorCode.GUARDIAN_LIMIT_REACHED);
        }
    }

    private void registerMutualGuardians(Long inviterId, Long inviteeId, LocalDateTime occurredAt) {
        List<Relationship> currentPair = relationshipRepository.findActiveBetweenUsers(
                inviterId, inviteeId, RelationshipStatus.ACTIVE);
        if (currentPair.stream().anyMatch(r -> r.getRelationshipType() == RelationshipType.GUARDIAN)) {
            throw new BusinessException(InvitationErrorCode.GUARDIAN_RELATIONSHIP_ALREADY_EXISTS);
        }

        // 보호자 관계가 생기면 두 방향의 기존 나의 사람 관계를 종료합니다.
        currentPair.stream()
                .filter(r -> r.getRelationshipType() == RelationshipType.MY_PERSON)
                .forEach(r -> r.end(occurredAt));
        relationshipRepository.flush();

        List<Relationship> inviterGuardians = findActiveGuardians(inviterId);
        List<Relationship> inviteeGuardians = findActiveGuardians(inviteeId);
        if (inviterGuardians.size() >= MAX_ACTIVE_GUARDIANS
                || inviteeGuardians.size() >= MAX_ACTIVE_GUARDIANS) {
            throw new BusinessException(InvitationErrorCode.GUARDIAN_LIMIT_REACHED);
        }

        // 기존 순위를 먼저 비우고 다시 매겨 새 보호자를 각 사용자의 묶음 끝에 추가합니다.
        List<Relationship> existingGuardians = new ArrayList<>(inviterGuardians);
        existingGuardians.addAll(inviteeGuardians);
        existingGuardians.forEach(Relationship::clearGuardianPriorityForReorder);
        relationshipRepository.saveAllAndFlush(existingGuardians);
        assignSequentialPriorities(inviterGuardians);
        assignSequentialPriorities(inviteeGuardians);
        relationshipRepository.saveAllAndFlush(existingGuardians);

        relationshipRepository.saveAllAndFlush(List.of(
                Relationship.createGuardian(inviterId, inviteeId, inviterGuardians.size() + 1),
                Relationship.createGuardian(inviteeId, inviterId, inviteeGuardians.size() + 1)
        ));
    }

    private List<Relationship> findActiveGuardians(Long userId) {
        return relationshipRepository.findAllBySourceUserIdAndRelationshipTypeAndStatusOrderByGuardianPriorityAsc(
                userId, RelationshipType.GUARDIAN, RelationshipStatus.ACTIVE);
    }

    private void assignSequentialPriorities(List<Relationship> guardians) {
        for (int index = 0; index < guardians.size(); index++) {
            guardians.get(index).changeGuardianPriority(index + 1);
        }
    }

    private String removeTrailingSlash(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("초대 링크 기본 주소(gyeot.invitation.base-url)가 필요합니다.");
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
