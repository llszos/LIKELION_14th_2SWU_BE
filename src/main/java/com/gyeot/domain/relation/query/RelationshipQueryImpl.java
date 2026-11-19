package com.gyeot.domain.relation.query;

import com.gyeot.domain.relation.entity.RelationshipStatus;
import com.gyeot.domain.relation.repository.RelationshipQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** 활성 관계를 대상 ID 목록으로 조회해 다른 영역용 요약 계약으로 변환합니다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RelationshipQueryImpl implements RelationshipQuery {

    private final RelationshipQueryRepository relationshipQueryRepository;

    @Override
    public List<RelationshipSummary> findActiveRelationships(
            Long sourceUserId,
            Collection<Long> targetUserIds
    ) {
        Objects.requireNonNull(sourceUserId, "sourceUserId는 필수입니다.");
        if (targetUserIds == null || targetUserIds.isEmpty()) {
            return List.of();
        }

        List<Long> distinctTargetUserIds = targetUserIds.stream()
                .filter(Objects::nonNull)
                .filter(targetUserId -> !sourceUserId.equals(targetUserId))
                .distinct()
                .toList();
        if (distinctTargetUserIds.isEmpty()) {
            return List.of();
        }

        return relationshipQueryRepository.findAllBySourceUserIdAndTargetUserIdInAndStatus(
                        sourceUserId, distinctTargetUserIds, RelationshipStatus.ACTIVE)
                .stream()
                .map(relationship -> new RelationshipSummary(
                        relationship.getTargetUserId(),
                        relationship.getRelationshipType(),
                        relationship.getPersonalLabel()))
                .toList();
    }
}
