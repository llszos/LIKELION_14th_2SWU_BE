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

/**
 * Processor의 트랜잭션 안에서 상황, 참여 이력 순서로 잠금 조회합니다.
 * 기존 참조는 detach되므로 호출자는 반환된 관리 엔티티를 사용해야 합니다.
 * refresh와 마찬가지로 대상의 미반영 로컬 변경을 보존하는 용도가 아닙니다.
 */
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
            // Reload into a fresh managed instance: same-lock refresh may use a snapshot read.
            return reloadLocked(entityManager, Situation.class, situation, situationId);
        });
    }

    @Override
    public List<SituationParticipant> findParticipantsForUpdate(Long situationId, Long userId) {
        List<SituationParticipant> history = participantRepository.findHistoryForUpdate(situationId, userId);
        return history.stream().map(participant ->
                reloadLocked(entityManager, SituationParticipant.class, participant, participant.getId())).toList();
    }

    static <T> T reloadLocked(EntityManager entityManager, Class<T> type, T entity, Long id) {
        entityManager.detach(entity);
        return entityManager.find(type, id, LockModeType.PESSIMISTIC_WRITE);
    }
}
