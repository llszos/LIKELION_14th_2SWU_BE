package com.gyeot.domain.relation.service;

import com.gyeot.domain.relation.entity.Relationship;
import com.gyeot.domain.relation.entity.RelationshipStatus;
import com.gyeot.domain.relation.entity.RelationshipType;
import com.gyeot.domain.relation.event.RelationshipEndedEvent;
import com.gyeot.domain.relation.exception.InvitationErrorCode;
import com.gyeot.domain.relation.exception.RelationshipErrorCode;
import com.gyeot.domain.relation.repository.RelationshipRepository;
import com.gyeot.domain.relation.dto.response.RelationshipEndResponse;
import com.gyeot.domain.user.entity.AccountStatus;
import com.gyeot.domain.user.entity.User;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class RelationshipService {

    private static final int MAX_ACTIVE_GUARDIANS = 3;

    private final RelationshipRepository relationshipRepository;
    private final UserRepository userRepository;
    private final EmergencyContactService emergencyContactService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public RelationshipService(
            RelationshipRepository relationshipRepository,
            UserRepository userRepository,
            EmergencyContactService emergencyContactService,
            ApplicationEventPublisher eventPublisher,
            Clock clock
    ) {
        this.relationshipRepository = relationshipRepository;
        this.userRepository = userRepository;
        this.emergencyContactService = emergencyContactService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /** 로그인 사용자가 등록한 관계를 종류에 맞게 종료하고 후속 영역에 한 번 알립니다. */
    @Transactional
    public RelationshipEndResponse endRelationship(Long userId, Long relationshipId) {
        if (userId == null) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }

        Relationship snapshot = relationshipRepository.findById(relationshipId)
                .orElseThrow(() -> new BusinessException(RelationshipErrorCode.RELATIONSHIP_NOT_FOUND));
        if (!snapshot.getSourceUserId().equals(userId)) {
            throw new BusinessException(RelationshipErrorCode.RELATIONSHIP_NOT_FOUND);
        }

        // 초대 수락·보호자 순서 변경과 같은 사용자 쌍 잠금 순서를 사용해 역방향 동시 요청의 교착을 막습니다.
        lockRelationshipUsers(snapshot.getSourceUserId(), snapshot.getTargetUserId());
        Relationship relationship = relationshipRepository.findByIdForUpdate(relationshipId)
                .orElseThrow(() -> new BusinessException(RelationshipErrorCode.RELATIONSHIP_NOT_FOUND));
        if (!relationship.getSourceUserId().equals(userId)) {
            throw new BusinessException(RelationshipErrorCode.RELATIONSHIP_NOT_FOUND);
        }
        if (relationship.getStatus() == RelationshipStatus.ENDED) {
            throw new BusinessException(RelationshipErrorCode.RELATIONSHIP_ALREADY_ENDED);
        }

        LocalDateTime endedAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        if (relationship.getRelationshipType() == RelationshipType.MY_PERSON) {
            endMyPerson(relationship, endedAt);
        } else {
            endGuardian(relationship, endedAt);
        }

        // GUARDIAN은 두 방향 행을 종료하지만, 관계 종료 사실은 한 건으로 B·C에 전달합니다.
        // 보호자 전환은 관계가 유지되는 변경이므로 이 이벤트를 발행하지 않습니다.
        eventPublisher.publishEvent(new RelationshipEndedEvent(
                relationship.getId(), relationship.getSourceUserId(), relationship.getTargetUserId(),
                relationship.getRelationshipType(), endedAt));
        return new RelationshipEndResponse(relationship.getId(), relationship.getRelationshipType(), endedAt);
    }

    private void endMyPerson(Relationship relationship, LocalDateTime endedAt) {
        relationship.end(endedAt);
        relationshipRepository.flush();
        emergencyContactService.removeForMyPersonRelationship(
                relationship.getSourceUserId(), relationship.getTargetUserId());
    }

    private void endGuardian(Relationship requestedRelationship, LocalDateTime endedAt) {
        List<Relationship> pair = relationshipRepository.findActiveBetweenUsersForUpdate(
                requestedRelationship.getSourceUserId(), requestedRelationship.getTargetUserId(), RelationshipStatus.ACTIVE);
        List<Relationship> guardians = pair.stream()
                .filter(relationship -> relationship.getRelationshipType() == RelationshipType.GUARDIAN)
                .toList();
        if (guardians.stream().noneMatch(relationship -> relationship.getId().equals(requestedRelationship.getId()))) {
            throw new BusinessException(RelationshipErrorCode.RELATIONSHIP_NOT_FOUND);
        }

        guardians.forEach(relationship -> relationship.end(endedAt));
        relationshipRepository.saveAllAndFlush(guardians);
        normalizeGuardianPriorities(requestedRelationship.getSourceUserId());
        normalizeGuardianPriorities(requestedRelationship.getTargetUserId());
    }

    private void normalizeGuardianPriorities(Long userId) {
        List<Relationship> guardians = findActiveGuardians(userId);
        if (guardians.isEmpty()) {
            return;
        }
        guardians.forEach(Relationship::clearGuardianPriorityForReorder);
        relationshipRepository.saveAllAndFlush(guardians);
        for (int index = 0; index < guardians.size(); index++) {
            guardians.get(index).changeGuardianPriority(index + 1);
        }
        relationshipRepository.saveAllAndFlush(guardians);
    }

    private void lockRelationshipUsers(Long firstUserId, Long secondUserId) {
        List<Long> ids = List.of(firstUserId, secondUserId).stream().sorted().toList();
        for (Long id : ids) {
            User user = userRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new BusinessException(RelationshipErrorCode.RELATIONSHIP_NOT_FOUND));
            if (id.equals(firstUserId) && user.getAccountStatus() != AccountStatus.ACTIVE) {
                throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
            }
        }
    }

    /** 로그인 사용자의 활성 보호자 전체 순서를 요청 목록과 정확히 일치하도록 변경합니다. */
    @Transactional
    public void changeGuardianOrder(Long userId, List<Long> targetUserIds) {
        lockActiveUser(userId);
        if (targetUserIds == null || targetUserIds.isEmpty()
                || targetUserIds.size() > MAX_ACTIVE_GUARDIANS
                || new HashSet<>(targetUserIds).size() != targetUserIds.size()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }

        List<Relationship> guardians = findActiveGuardians(userId);
        Set<Long> currentIds = guardians.stream()
                .map(Relationship::getTargetUserId)
                .collect(java.util.stream.Collectors.toSet());
        if (currentIds.size() != guardians.size()
                || currentIds.size() != targetUserIds.size()
                || !currentIds.equals(new HashSet<>(targetUserIds))) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }

        guardians.forEach(Relationship::clearGuardianPriorityForReorder);
        relationshipRepository.saveAllAndFlush(guardians);

        for (int index = 0; index < targetUserIds.size(); index++) {
            Long targetUserId = targetUserIds.get(index);
            guardians.stream()
                    .filter(r -> r.getTargetUserId().equals(targetUserId))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_INPUT))
                    .changeGuardianPriority(index + 1);
        }
        relationshipRepository.saveAllAndFlush(guardians);
    }

    private List<Relationship> findActiveGuardians(Long userId) {
        return relationshipRepository.findAllBySourceUserIdAndRelationshipTypeAndStatusOrderByGuardianPriorityAsc(
                userId, RelationshipType.GUARDIAN, RelationshipStatus.ACTIVE);
    }

    private User lockActiveUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(InvitationErrorCode.INVITATION_USER_NOT_AVAILABLE));
        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException(InvitationErrorCode.INVITATION_USER_NOT_AVAILABLE);
        }
        return user;
    }

}
