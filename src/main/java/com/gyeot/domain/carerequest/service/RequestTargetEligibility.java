package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.relation.entity.Relationship;
import com.gyeot.domain.relation.entity.RelationshipStatus;
import com.gyeot.domain.relation.entity.RelationshipType;
import com.gyeot.domain.relation.repository.RelationshipQueryRepository;
import com.gyeot.domain.situation.query.SituationParticipantReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 요청 대상 자격을 한곳에서 판단합니다. (F-05, F-14)
 * 요청 생성(#69), 다시 부탁하기(#71), 대상 후보 조회(#108)가 같은 기준을 쓰도록 모았습니다.
 * 한 사람을 확인할 때는 isEligible, 후보 목록을 만들 때는 findCandidates를 쓰며 두 메서드의 기준은 같습니다.
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

    /**
     * 이 상황에서 요청자가 요청을 보낼 수 있는 후보 목록 (대상 후보 조회 #108)
     * isEligible과 같은 기준입니다: 상황의 ACTIVE 참여자 ∩ 요청자가 등록한 ACTIVE 관계의 대상 − 본인
     *
     * 정렬 (F-26 보호자 맨 위)
     * - 서로의 보호자 먼저: 보호자 순서(guardianPriority) 순, 같으면 상황 참여 순
     * - 그다음 나의 사람: 상황 참여 순 (B가 joinedAt, 참여 행 ID 순으로 줌)
     * B는 같은 사람의 ACTIVE 참여 행이 여러 개면 ID를 중복으로 줄 수 있어 여기서 한 번만 담습니다.
     * 조회는 B 1번 + A 1번입니다. (참여자 수만큼 조회하지 않음)
     */
    public List<Candidate> findCandidates(Long situationId, Long requesterId) {
        Set<Long> participantIds = new LinkedHashSet<>(situationReader.findActiveParticipantIds(situationId)); // 참여 순, 중복 제거
        participantIds.remove(requesterId);
        if (participantIds.isEmpty()) {
            return List.of();
        }

        // 요청자 → 대상 방향의 ACTIVE 관계 (같은 사람에게 나의 사람·보호자 행이 함께 있으면 보호자로 봄)
        Map<Long, Relationship> relationByTarget = new HashMap<>();
        for (Relationship relationship : relationshipRepository.findAllBySourceUserIdAndStatus(
                requesterId, RelationshipStatus.ACTIVE)) {
            relationByTarget.merge(relationship.getTargetUserId(), relationship,
                    (existing, other) -> other.getRelationshipType() == RelationshipType.GUARDIAN ? other : existing);
        }

        List<Candidate> candidates = new ArrayList<>();
        int participantOrder = 0;
        for (Long userId : participantIds) {
            Relationship relationship = relationByTarget.get(userId);
            if (relationship != null) {
                boolean guardian = relationship.getRelationshipType() == RelationshipType.GUARDIAN;
                candidates.add(new Candidate(userId, guardian, guardian ? relationship.getGuardianPriority() : null,
                        participantOrder));
            }
            participantOrder++;
        }
        candidates.sort(CANDIDATE_ORDER);
        return candidates;
    }

    private static final Comparator<Candidate> CANDIDATE_ORDER = Comparator
            .comparing((Candidate candidate) -> !candidate.isGuardian()) // 보호자(false) 먼저
            .thenComparing(Candidate::guardianPriority, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingInt(Candidate::participantOrder);

    /**
     * 대상 후보 한 명
     * - isGuardian: 요청자와 서로의 보호자면 true, 나의 사람이면 false
     * - guardianPriority, participantOrder: 정렬에만 쓰는 값 (응답에는 내보내지 않음)
     */
    public record Candidate(Long userId, boolean isGuardian, Integer guardianPriority, int participantOrder) {
    }
}