package com.gyeot.domain.relation.query;

import com.gyeot.domain.relation.entity.RelationshipType;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 다른 영역에서 대상 사용자들의 활성 관계 정보를 한 번에 조회하는 계약입니다.
 * 관계 방향·유형·상태와 관계 표시 정보만 제공하며 사용자 계정 상태, 상황·참여 상태,
 * 호출 영역의 권한이나 업무 자격은 확인하지 않습니다.
 */
public interface RelationshipQuery {

    /**
     * source 사용자에서 각 target 사용자 방향의 ACTIVE 관계만 조회합니다.
     * 역방향 관계만 있거나 관계가 없는 대상, source 본인은 결과에서 제외합니다.
     * personalLabel은 source 사용자가 target 사용자에게 지정한 표시명입니다.
     * 입력 대상의 null과 중복은 무시하며 반환 순서는 보장하지 않습니다.
     * sourceUserId는 필수이고, targetUserIds가 null 또는 비어 있으면 빈 목록을 반환합니다.
     *
     * @param sourceUserId 관계를 확인할 기준 사용자 ID
     * @param targetUserIds 관계를 확인할 대상 사용자 ID 목록
     * @return 관계가 존재하는 대상별 관계 요약 목록
     * @throws NullPointerException sourceUserId가 null인 경우
     */
    List<RelationshipSummary> findActiveRelationships(
            Long sourceUserId,
            Collection<Long> targetUserIds
    );

    /** 다른 영역에 제공할 대상 사용자 ID와 활성 관계 표시 정보입니다. */
    record RelationshipSummary(
            Long targetUserId,
            RelationshipType relationshipType,
            String personalLabel
    ) {
        public RelationshipSummary {
            Objects.requireNonNull(targetUserId, "targetUserId는 필수입니다.");
            Objects.requireNonNull(relationshipType, "relationshipType은 필수입니다.");
        }
    }
}
