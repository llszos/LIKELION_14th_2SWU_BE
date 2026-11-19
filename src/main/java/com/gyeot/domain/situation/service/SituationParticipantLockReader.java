package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.entity.SituationParticipant;
import com.gyeot.domain.situation.repository.SituationParticipantRepository;
import com.gyeot.domain.situation.repository.SituationRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/** 내부 변경용 조회입니다. 호출자의 쓰기 트랜잭션이 잠금을 유지하며 HTTP·업무 정책은 판단하지 않습니다. */
@Repository
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class SituationParticipantLockReader {
    private final SituationRepository situations;
    private final SituationParticipantRepository participants;
    private final EntityManager entityManager;

    /** 상황 또는 해당 상황의 참여 이력이 없으면 empty를 반환합니다. 상태·역할은 제한하지 않습니다. */
    public Optional<SituationParticipant> findForUpdate(Long situationId, Long participantId) {
        Objects.requireNonNull(situationId, "situationId는 필수입니다.");
        Objects.requireNonNull(participantId, "participantId는 필수입니다.");
        return situations.findForUpdate(situationId)
                .flatMap(situation -> participants.findBySituationAndParticipantForUpdate(situationId, participantId))
                .map(p -> JpaSituationLeaveStore.reloadLocked(entityManager, SituationParticipant.class, p, participantId));
    }
}
