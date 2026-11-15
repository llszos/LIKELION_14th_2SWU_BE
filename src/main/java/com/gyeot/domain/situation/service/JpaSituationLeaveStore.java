package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.entity.SituationParticipant;
import com.gyeot.domain.situation.repository.SituationRepository;
import com.gyeot.domain.situation.repository.SituationParticipantRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Processor의 트랜잭션 안에서 상황, 참여 이력 순서로 잠금 조회합니다. */
@Repository
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class JpaSituationLeaveStore implements SituationLeaveStore {
    private final SituationRepository situationRepository;
    private final SituationParticipantRepository participantRepository;
    private final EntityManager entityManager;

    @Override
    public Optional<Situation> findSituationForUpdate(Long situationId) {
        return situationRepository.findForUpdate(situationId).map(situation -> {
            // 이미 로딩된 엔티티도 잠금 획득 후 최신 DB 상태로 갱신합니다.
            entityManager.refresh(situation, LockModeType.PESSIMISTIC_WRITE);
            return situation;
        });
    }

    @Override
    public List<SituationParticipant> findParticipantsForUpdate(Long situationId, Long userId) {
        List<SituationParticipant> history = participantRepository.findHistoryForUpdate(situationId, userId);
        history.forEach(p -> entityManager.refresh(p, LockModeType.PESSIMISTIC_WRITE));
        return history;
    }
}
