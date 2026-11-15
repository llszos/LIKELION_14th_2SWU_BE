package com.gyeot.domain.relation.repository;

import com.gyeot.domain.relation.entity.Relationship;
import com.gyeot.domain.relation.entity.RelationshipStatus;
import com.gyeot.domain.relation.entity.RelationshipType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;


/** 나의 사람·보호자 관계를 저장하고 조회합니다. */
public interface RelationshipRepository extends JpaRepository<Relationship, Long> {

    /** 관계 해제 경합에서 동일한 관계 행을 직렬화합니다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Relationship r where r.id = :relationshipId")
    Optional<Relationship> findByIdForUpdate(@Param("relationshipId") Long relationshipId);

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

    // 사용자의 활성 보호자를 현재 순서대로 조회합니다.
    List<Relationship> findAllBySourceUserIdAndRelationshipTypeAndStatusOrderByGuardianPriorityAsc(
            Long sourceUserId,
            RelationshipType relationshipType,
            RelationshipStatus status
    );

    List<Relationship> findAllBySourceUserIdAndRelationshipTypeAndStatus(
            Long sourceUserId,
            RelationshipType relationshipType,
            RelationshipStatus status
    );

    // 두 사용자 사이의 활성 관계를 방향과 유형에 상관없이 조회합니다.
    @Query("""
            select r from Relationship r
            where r.status = :status
              and ((r.sourceUserId = :firstUserId and r.targetUserId = :secondUserId)
                or (r.sourceUserId = :secondUserId and r.targetUserId = :firstUserId))
            """)
    List<Relationship> findActiveBetweenUsers(
            @Param("firstUserId") Long firstUserId,
            @Param("secondUserId") Long secondUserId,
            @Param("status") RelationshipStatus status
    );

    /** 양방향 보호자 해제에 필요한 두 사용자 사이의 활성 관계를 잠금 조회합니다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r from Relationship r
            where r.status = :status
              and ((r.sourceUserId = :firstUserId and r.targetUserId = :secondUserId)
                or (r.sourceUserId = :secondUserId and r.targetUserId = :firstUserId))
            """)
    List<Relationship> findActiveBetweenUsersForUpdate(
            @Param("firstUserId") Long firstUserId,
            @Param("secondUserId") Long secondUserId,
            @Param("status") RelationshipStatus status
    );

}
