package com.gyeot.domain.relation.service;

import com.gyeot.domain.relation.entity.Relationship;
import com.gyeot.domain.relation.entity.RelationshipStatus;
import com.gyeot.domain.relation.entity.RelationshipType;
import com.gyeot.domain.relation.exception.InvitationErrorCode;
import com.gyeot.domain.relation.repository.RelationshipRepository;
import com.gyeot.domain.user.entity.AccountStatus;
import com.gyeot.domain.user.entity.User;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class RelationshipService {

    private static final int MAX_ACTIVE_GUARDIANS = 3;

    private final RelationshipRepository relationshipRepository;
    private final UserRepository userRepository;

    public RelationshipService(RelationshipRepository relationshipRepository, UserRepository userRepository) {
        this.relationshipRepository = relationshipRepository;
        this.userRepository = userRepository;
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
