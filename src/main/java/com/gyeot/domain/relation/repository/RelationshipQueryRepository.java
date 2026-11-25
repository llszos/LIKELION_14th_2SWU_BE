package com.gyeot.domain.relation.repository;

import com.gyeot.domain.relation.entity.Relationship;
import com.gyeot.domain.relation.entity.RelationshipStatus;
import com.gyeot.domain.relation.entity.RelationshipType;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;

/**
 * 관계 조회에 사용하는 조회 전용 Repository입니다.
 *
 * 관계 조회는 sourceUserId → targetUserId 방향을 기준으로 합니다.
 * 반대 방향의 관계는 별도로 조회해야 합니다.
 *
 * 관계 자격 확인 시에는 활성 상태를 조건으로 전달해야 합니다.
 * 상황 참여 여부와 요청 상태는 각 담당 영역에서 별도로 검증합니다.
 *
 * 별도 OrderBy 조건이 없는 목록 조회 결과의 순서는 보장하지 않습니다.
 */
public interface RelationshipQueryRepository
        extends Repository<Relationship, Long> {

    /**
     * 특정 방향과 상태의 관계가 존재하는지 확인합니다.
     * 나의 사람과 보호자를 모두 포함하며 관계 유형은 구분하지 않습니다.
     */
    boolean existsBySourceUserIdAndTargetUserIdAndStatus(
            Long sourceUserId,
            Long targetUserId,
            RelationshipStatus status
    );

    /**
     * 특정 방향·유형·상태의 관계가 존재하는지 확인합니다.
     * 보호자 여부 확인 등에 사용합니다.
     */
    boolean existsBySourceUserIdAndTargetUserIdAndRelationshipTypeAndStatus(
            Long sourceUserId,
            Long targetUserId,
            RelationshipType relationshipType,
            RelationshipStatus status
    );


    // 사용자가 등록한 관계를 상태별로 조회합니다.
    List<Relationship> findAllBySourceUserIdAndStatus(
            Long sourceUserId,
            RelationshipStatus status
    );

    // 사용자를 관계 대상으로 등록한 관계를 상태별로 조회합니다.
    List<Relationship> findAllByTargetUserIdAndStatus(
            Long targetUserId,
            RelationshipStatus status
    );

    /** 로그인 사용자가 나의 사람 관계의 대상자인 활성 관계를 최근 등록순으로 조회합니다. */
    List<Relationship> findAllByTargetUserIdAndRelationshipTypeAndStatusOrderByCreatedAtDescIdDesc(
            Long targetUserId,
            RelationshipType relationshipType,
            RelationshipStatus status
    );

    /** 특정 사용자가 등록한 대상 ID 목록의 관계만 한 번에 조회합니다. */
    List<Relationship> findAllBySourceUserIdAndTargetUserIdInAndStatus(
            Long sourceUserId,
            Collection<Long> targetUserIds,
            RelationshipStatus status
    );
}
