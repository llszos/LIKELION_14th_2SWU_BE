package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.dto.response.SituationParticipantAddResponse;
import com.gyeot.domain.situation.entity.*;
import com.gyeot.domain.situation.exception.*;
import com.gyeot.domain.situation.repository.*;
import com.gyeot.domain.relation.repository.RelationshipQueryRepository;
import com.gyeot.domain.relation.entity.RelationshipStatus;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.domain.user.entity.AccountStatus;
import com.gyeot.global.exception.*;
import com.gyeot.global.security.auth.AuthErrorCode;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;

/**
 * 팀 확인 전 임시 정책: ACTIVE OWNER만 추가, 생성자→대상 ACTIVE 관계 필요,
 * 비활성 이력은 보존하고 새 행 생성, 본인 거부. 관계 실패는 공통 FORBIDDEN 사용.
 * 같은 상황의 모든 추가 경로가 부모 잠금을 지켜야 중복 검사가 직렬화됩니다.
 */
@Service @RequiredArgsConstructor
public class SituationParticipantAddService {
    private final SituationRepository situations;
    private final SituationParticipantRepository participants;
    private final RelationshipQueryRepository relationships;
    private final UserRepository users;
    private final EntityManager em;
    private final Clock clock;

    @Transactional
    public SituationParticipantAddResponse add(Long situationId, Long callerId, Long targetId) {
        var situation = situations.findForUpdate(situationId)
                .map(s -> JpaSituationLeaveStore.reloadLocked(em, Situation.class, s, situationId))
                .orElseThrow(() -> new BusinessException(SituationRemovalErrorCode.SITUATION_NOT_FOUND));
        var callerHistory = participants.findHistoryForUpdate(situationId, callerId).stream()
                .map(p -> JpaSituationLeaveStore.reloadLocked(em, SituationParticipant.class, p, p.getId()))
                .toList();
        if (!situation.getCreatorId().equals(callerId) || callerHistory.stream().noneMatch(p ->
                p.getRole() == SituationParticipantRole.OWNER && p.getStatus() == SituationParticipantStatus.ACTIVE))
            throw new BusinessException(SituationParticipantAddErrorCode.SITUATION_FORBIDDEN);
        if (situation.getStatus() != SituationStatus.ACTIVE)
            throw new BusinessException(SituationLeaveErrorCode.SITUATION_ENDED);
        if (targetId == null || callerId.equals(targetId))
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        if (!users.existsById(targetId)) throw new BusinessException(AuthErrorCode.USER_NOT_FOUND);
        if (users.existsByIdAndAccountStatus(targetId, AccountStatus.WITHDRAWN))
            throw new BusinessException(AuthErrorCode.USER_ALREADY_WITHDRAWN);
        if (!users.existsByIdAndAccountStatus(targetId, AccountStatus.ACTIVE))
            throw new BusinessException(AuthErrorCode.USER_NOT_ACTIVE);
        if (!relationships.existsBySourceUserIdAndTargetUserIdAndStatus(
                situation.getCreatorId(), targetId, RelationshipStatus.ACTIVE))
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        boolean alreadyActive = participants.findHistoryForUpdate(situationId, targetId).stream()
                .map(p -> JpaSituationLeaveStore.reloadLocked(em, SituationParticipant.class, p, p.getId()))
                .anyMatch(p -> p.getStatus() == SituationParticipantStatus.ACTIVE);
        if (alreadyActive)
            throw new BusinessException(SituationParticipantAddErrorCode.SITUATION_PARTICIPANT_ALREADY_EXISTS);
        var joinedAt = LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))).truncatedTo(ChronoUnit.MICROS);
        var saved = participants.save(SituationParticipant.create(situation, targetId,
                SituationParticipantRole.PARTICIPANT, joinedAt));
        return SituationParticipantAddResponse.from(saved);
    }
}
