package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.relation.entity.Relationship;
import com.gyeot.domain.relation.entity.RelationshipStatus;
import com.gyeot.domain.relation.entity.RelationshipType;
import com.gyeot.domain.relation.repository.RelationshipQueryRepository;
import com.gyeot.domain.situation.query.SituationParticipantReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 요청 대상 자격을 한곳에서 판단합니다. (F-05, F-14)
 * 요청 생성(#69), 다시 부탁하기(#71), 대상 후보 조회(#108)가 같은 기준을 쓰도록 모았습니다.
 *
 * 대상이 될 수 있는 사람 = 아래를 모두 만족
 * - 요청자 본인이 아님
 * - 상황의 ACTIVE 참여자 (B)
 * - 요청자가 등록한 ACTIVE 관계의 대상 (A, source = 요청자, target = 대상, 나의 사람·보호자 모두)
 *   나의 사람은 등록한 사람이 source인 한 줄, 보호자는 양방향 두 줄로 저장되므로 이 방향 하나만 보면 됩니다.
 */
@Component
@RequiredArgsConstructor
public class RequestTargetEligibility {

    private final SituationParticipantReader situationReader;     // B: 상황 참여 여부
    private final RelationshipQueryRepository relationshipRepository; // A: 관계

    /** 이 상황에서 요청자가 이 사람에게 요청을 보낼 수 있는지 */
    public boolean isEligible(Long situationId, Long requesterId, Long targetUserId) {
        if (targetUserId == null || targetUserId.equals(requesterId)) {
            return false;
        }
        return situationReader.isActiveParticipant(situationId, targetUserId)
                && relationshipRepository.existsBySourceUserIdAndTargetUserIdAndStatus(
                        requesterId, targetUserId, RelationshipStatus.ACTIVE);
    }

    /** 요청자와 이 사람이 서로의 보호자인지 (ACTIVE 관계 기준, 해제됐으면 false) */
    public boolean isGuardian(Long requesterId, Long targetUserId) {
        return relationshipRepository.existsBySourceUserIdAndTargetUserIdAndRelationshipTypeAndStatus(
                requesterId, targetUserId, RelationshipType.GUARDIAN, RelationshipStatus.ACTIVE);
    }

    /**
     * 여러 사람 중 요청자와 서로의 보호자인 사람의 ID (요청 상세의 부탁 순서처럼 여러 명을 한 번에 볼 때)
     * 사람 수만큼 조회하지 않도록 요청자의 ACTIVE 관계를 한 번만 가져옵니다.
     */
    public Set<Long> guardianIdsAmong(Long requesterId, Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Set.of();
        }
        return relationshipRepository.findAllBySourceUserIdAndStatus(requesterId, RelationshipStatus.ACTIVE)
                .stream()
                .filter(relationship -> relationship.getRelationshipType() == RelationshipType.GUARDIAN)
                .map(Relationship::getTargetUserId)
                .filter(userIds::contains)
                .collect(Collectors.toUnmodifiableSet());
    }
}
