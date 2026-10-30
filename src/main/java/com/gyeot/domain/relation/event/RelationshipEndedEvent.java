package com.gyeot.domain.relation.event;

import com.gyeot.domain.relation.entity.RelationshipType;

/**
 * 사용자가 관계를 해제했을 때 발행하는 이벤트입니다.
 *
 * 나의 사람 관계는 sourceUserId → targetUserId 방향만 해제합니다.
 * 보호자 관계는 양방향 관계를 모두 해제한 뒤 이벤트를 한 번만 발행하며,
 * 수신 영역에서는 양쪽 방향의 관련 데이터를 정리합니다.
 *
 * 기존 나의 사람 관계를 보호자 관계로 대체하는 과정에서는
 * 관계가 단절되는 것이 아니므로 이 이벤트를 발행하지 않습니다.
 */
public record RelationshipEndedEvent(
        Long relationshipId, // 해제를 요청한 사용자 → 상대 사용자 방향의 관계 행 ID
        Long sourceUserId, // 관계 해제를 요청한 사용자 ID
        Long targetUserId, // 관계가 해제된 상대 사용자 ID
        RelationshipType relationshipType // 해제 직전의 관계 유형
) {
}
