package com.gyeot.domain.relation.repository;

import com.gyeot.domain.relation.entity.Relationship;
import com.gyeot.domain.relation.entity.RelationshipStatus;
import com.gyeot.domain.relation.entity.RelationshipType;
import org.springframework.data.jpa.repository.JpaRepository;


/** 나의 사람 관계를 생성하고 중복 여부를 확인합니다. */
public interface RelationshipRepository extends JpaRepository<Relationship, Long> {

    // 같은 방향의 활성 관계가 이미 있는지 확인합니다.
    boolean existsBySourceUserIdAndTargetUserIdAndRelationshipTypeAndStatus(
            Long sourceUserId,
            Long targetUserId,
            RelationshipType relationshipType,
            RelationshipStatus status
    );

    // 사용자의 활성 보호자 수를 확인합니다. 양방향 관계는 사용자별 한 행씩 저장됩니다.
    long countBySourceUserIdAndRelationshipTypeAndStatus(
            Long sourceUserId,
            RelationshipType relationshipType,
            RelationshipStatus status
    );

}
