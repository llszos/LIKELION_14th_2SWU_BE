package com.gyeot.domain.situation.service;

import com.gyeot.domain.relation.entity.RelationshipType;
import com.gyeot.domain.relation.event.RelationshipEndedEvent;
import com.gyeot.domain.situation.entity.*;
import com.gyeot.domain.situation.event.SituationParticipantRemovedEvent;
import com.gyeot.domain.situation.event.SituationParticipantRemovalReason;
import com.gyeot.domain.situation.repository.SituationCleanupRepository;
import com.gyeot.domain.user.event.UserWithdrawnEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.HashSet;
import java.util.Objects;

/**
 * A의 원본 트랜잭션에 참여합니다. 동기 C/D 수신 실패도 원본 작업과 함께 롤백합니다.
 * OWNER 탈퇴 시 상황 삭제/종료는 C 기록 보존·FK 정책 합의 전까지 이 서비스의 범위에서 제외합니다.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class SituationMembershipCleanupService {
    private final SituationCleanupRepository repository;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher publisher;

    public void relationshipEnded(RelationshipEndedEvent event) {
        Objects.requireNonNull(event.sourceUserId());
        Objects.requireNonNull(event.targetUserId());
        Objects.requireNonNull(event.relationshipType());
        Objects.requireNonNull(event.occurredAt());
        var ownerIds = event.relationshipType() == RelationshipType.GUARDIAN
                ? List.of(event.sourceUserId(), event.targetUserId()) : List.of(event.sourceUserId());
        for (Situation candidate : repository.lockOwnedSituations(ownerIds)) {
            Situation situation = reloadLocked(candidate, Situation.class, candidate.getId());
            if (situation.getStatus() != SituationStatus.ACTIVE) continue;
            Long targetId = situation.getCreatorId().equals(event.sourceUserId())
                    ? event.targetUserId() : event.sourceUserId();
            var participants = currentParticipants(situation.getId());
            boolean ownerPresent = participants.stream().anyMatch(p ->
                    p.getUserId().equals(situation.getCreatorId())
                            && p.getRole() == SituationParticipantRole.OWNER
                            && p.getStatus() == SituationParticipantStatus.ACTIVE);
            if (!ownerPresent) continue;
            for (SituationParticipant participant : participants) {
                if (!canEnd(participant, targetId, event.occurredAt())) continue;
                participant.remove(event.occurredAt());
                publisher.publishEvent(new SituationParticipantRemovedEvent(situation.getId(), targetId,
                        SituationParticipantRemovalReason.RELATIONSHIP_ENDED, participant.getLeftAt()));
            }
        }
    }

    /** ERD 9.6: 타인 ACTIVE 상황의 일반 참여만 LEFT. C는 원본 UserWithdrawnEvent를 직접 받습니다. */
    public void userWithdrawn(UserWithdrawnEvent event) {
        Objects.requireNonNull(event.userId());
        Objects.requireNonNull(event.occurredAt());
        for (Situation candidate : repository.lockParticipatedSituations(event.userId())) {
            Situation situation = reloadLocked(candidate, Situation.class, candidate.getId());
            if (situation.getStatus() != SituationStatus.ACTIVE
                    || situation.getCreatorId().equals(event.userId())) continue;
            for (SituationParticipant participant : currentParticipants(situation.getId())) {
                if (canEnd(participant, event.userId(), event.occurredAt())) participant.leave(event.occurredAt());
            }
        }
    }

    private boolean canEnd(SituationParticipant participant, Long userId, LocalDateTime occurredAt) {
        return participant.getUserId().equals(userId)
                && participant.getRole() == SituationParticipantRole.PARTICIPANT
                && participant.getStatus() == SituationParticipantStatus.ACTIVE
                && !participant.getJoinedAt().isAfter(occurredAt);
    }

    private List<SituationParticipant> currentParticipants(Long situationId) {
        var participants = repository.lockParticipants(situationId).stream()
                .map(p -> reloadLocked(p, SituationParticipant.class, p.getId())).toList();
        var activeUsers = new HashSet<Long>();
        for (var participant : participants) {
            if (participant.getStatus() == SituationParticipantStatus.ACTIVE
                    && !activeUsers.add(participant.getUserId())) {
                throw new DataIntegrityViolationException("동일 상황·사용자의 ACTIVE 참여 행이 여러 개입니다.");
            }
        }
        return participants;
    }

    /**
     * A가 먼저 만든 RR 스냅샷/기존 관리 객체 대신 잠금 읽기의 현재 상태를 사용합니다.
     * detach 전에 flush하여 같은 트랜잭션의 미반영 변경을 잃지 않습니다. 다른 객체는 detach하지 않습니다.
     */
    private <T> T reloadLocked(T entity, Class<T> type, Long id) {
        entityManager.flush();
        entityManager.detach(entity);
        return entityManager.find(type, id, LockModeType.PESSIMISTIC_WRITE);
    }
}
