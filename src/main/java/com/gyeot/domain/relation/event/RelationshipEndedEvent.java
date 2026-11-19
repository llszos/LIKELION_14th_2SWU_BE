package com.gyeot.domain.relation.event;

import com.gyeot.domain.relation.entity.RelationshipType;

import java.time.LocalDateTime;

/**
 * 사용자가 관계를 해제했을 때 발행하는 이벤트입니다. 관계 상태 변경과 같은 트랜잭션에서 한 번 발행합니다.
 * 현재 계약은 동기 원자 처리입니다. A가 트랜잭션 안에서 발행하면 B·C·D의 동기 리스너도 같은
 * 트랜잭션에 참여하며, 어느 단계에서든 예외가 발생하면 전체 변경을 롤백합니다. 커밋 후 또는
 * 비동기 처리로 바꾸려면 실패·재시도 보장까지 포함한 별도 계약 변경이 필요합니다.
 *
 * 나의 사람 관계는 sourceUserId → targetUserId 방향만 해제합니다.
 * 보호자 관계는 양방향 관계를 모두 해제한 뒤 이벤트를 한 번만 발행합니다.
 * 보호자 해제 이벤트의 relationshipId·sourceUserId·targetUserId는 로그인 사용자가 해제 요청에 사용한 방향을
 * 나타내며, B·C 수신자는 두 사용자 쌍의 양쪽 방향 데이터를 정리해야 합니다.
 *
 * 기존 나의 사람 관계를 보호자 관계로 대체하는 과정에서는
 * 관계가 단절되는 것이 아니므로 이 이벤트를 발행하지 않습니다.
 * 자세한 처리 시점과 통합 검증 기준은 {@code docs/domain-event-transactions.md}를 참고합니다.
 *
 * @param relationshipId 해제 요청에 사용한 방향의 관계 ID (relationships.id)
 * @param sourceUserId 관계를 등록한 사용자 ID (users.id)
 * @param targetUserId 관계 대상 사용자 ID (users.id)
 * @param relationshipType 종료된 관계 유형. MY_PERSON은 해당 방향, GUARDIAN은 양방향 종료를 뜻합니다.
 * @param occurredAt 관계 종료 시각 (Asia/Seoul 기준)
 */
public record RelationshipEndedEvent(
        Long relationshipId,
        Long sourceUserId,
        Long targetUserId,
        RelationshipType relationshipType,
        LocalDateTime occurredAt
) {}


