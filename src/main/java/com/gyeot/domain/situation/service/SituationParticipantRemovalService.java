package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.dto.internal.SituationParticipantRemovalResult;
import com.gyeot.domain.situation.entity.*;
import com.gyeot.domain.situation.exception.SituationLeaveErrorCode;
import com.gyeot.domain.situation.exception.SituationRemovalErrorCode;
import com.gyeot.domain.situation.repository.SituationParticipantRepository;
import com.gyeot.domain.situation.repository.SituationRepository;
import com.gyeot.global.exception.BusinessException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 팀 최종 승인 전 구현 가정: ACTIVE OWNER 권한, 종료 상황 거부, 비활성 대상 재요청 성공. */
@Service
@RequiredArgsConstructor
public class SituationParticipantRemovalService {
    private final SituationRepository situations;
    private final SituationParticipantRepository participants;
    private final SituationParticipantLockReader reader;
    private final SituationParticipantRemovalProcessor processor;
    private final EntityManager em;

    @Transactional
    public SituationParticipantRemovalResult remove(Long situationId, Long participantId, Long callerId) {
        var situation = situations.findForUpdate(situationId)
                .orElseThrow(() -> new BusinessException(SituationRemovalErrorCode.SITUATION_NOT_FOUND));
        em.refresh(situation, LockModeType.PESSIMISTIC_WRITE);
        // 상황 잠금 뒤 호출자의 이력을 잠급니다. 기존 나가기와 같은 부모 → 자식 순서입니다.
        var callerHistory = participants.findHistoryForUpdate(situationId, callerId);
        callerHistory.forEach(p -> em.refresh(p, LockModeType.PESSIMISTIC_WRITE));
        boolean activeOwner = situation.getCreatorId().equals(callerId) && callerHistory.stream().anyMatch(p ->
                p.getRole() == SituationParticipantRole.OWNER && p.getStatus() == SituationParticipantStatus.ACTIVE);
        if (!activeOwner) throw new BusinessException(SituationRemovalErrorCode.SITUATION_FORBIDDEN);
        if (situation.getStatus() == SituationStatus.ENDED)
            throw new BusinessException(SituationLeaveErrorCode.SITUATION_ENDED);
        // Reader의 부모 재조회는 같은 트랜잭션/잠금 안에서 수행합니다.
        var target = reader.findForUpdate(situationId, participantId)
                .orElseThrow(() -> new BusinessException(SituationRemovalErrorCode.SITUATION_PARTICIPANT_NOT_FOUND));
        if (target.getRole() == SituationParticipantRole.OWNER)
            throw new BusinessException(SituationRemovalErrorCode.SITUATION_OWNER_REMOVAL_NOT_ALLOWED);
        processor.removeValidated(situationId, target);
        return SituationParticipantRemovalResult.from(target);
    }
}
