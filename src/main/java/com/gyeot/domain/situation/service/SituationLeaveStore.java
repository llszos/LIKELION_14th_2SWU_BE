package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.entity.SituationParticipant;

import java.util.List;
import java.util.Optional;

/**
 * 나가기 변경 작업의 잠금 조회 계약입니다. JpaSituationLeaveStore가 기존 Repository에 연결합니다.
 * 같은 트랜잭션에서 상황을 먼저 PESSIMISTIC_WRITE로 조회한 뒤 참여 이력을 조회해야 합니다.
 * 반환 엔티티는 관리 상태여야 하며, 참여 이력은 상태 필터 없이 모두 반환합니다.
 * 잠금은 커밋까지 유지하고 이미 영속성 컨텍스트에 있던 값도 최신 상태로 읽어야 합니다.
 * 호출자는 Spring 트랜잭션 안에서 사용해야 합니다.
 */
public interface SituationLeaveStore {
    Optional<Situation> findSituationForUpdate(Long situationId);

    /** 해당 상황·사용자의 모든 참여 행을 잠금 조회합니다. 반환 순서에는 의존하지 않습니다. */
    List<SituationParticipant> findParticipantsForUpdate(Long situationId, Long userId);
}
