package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.dto.response.SituationLeaveResponse;
import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.entity.SituationParticipant;
import com.gyeot.domain.situation.entity.SituationParticipantRole;
import com.gyeot.domain.situation.entity.SituationParticipantStatus;
import com.gyeot.domain.situation.entity.SituationStatus;
import com.gyeot.domain.situation.event.SituationParticipantRemovalReason;
import com.gyeot.domain.situation.event.SituationParticipantRemovedEvent;
import com.gyeot.domain.situation.exception.SituationLeaveErrorCode;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 나가기 업무 처리입니다. DB 구현 연결 전이므로 컴포넌트 스캔 대상이 아닙니다.
 * 연결 시 반드시 Spring Bean으로 등록하여 트랜잭션 프록시를 통해 호출해야 합니다.
 * 반복 요청의 성공 반환과 이력 선택 정책은 docs/situation-leave.md의 구현 가정입니다.
 */
public class SituationLeaveProcessor {
    private final SituationLeaveStore store;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public SituationLeaveProcessor(SituationLeaveStore store, ApplicationEventPublisher eventPublisher, Clock clock) {
        this.store = store;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public SituationLeaveResponse leave(Long situationId, Long userId) {
        Objects.requireNonNull(situationId, "situationId는 필수입니다.");
        Objects.requireNonNull(userId, "userId는 필수입니다.");
        Situation situation = store.findSituationForUpdate(situationId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        if (situation.getStatus() == SituationStatus.ENDED) {
            throw new BusinessException(SituationLeaveErrorCode.SITUATION_ENDED);
        }

        SituationParticipant participant = selectParticipant(store.findParticipantsForUpdate(situationId, userId));
        if (participant.getRole() == SituationParticipantRole.OWNER) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        if (participant.getStatus() == SituationParticipantStatus.ACTIVE) {
            LocalDateTime occurredAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
            participant.leave(occurredAt);
            eventPublisher.publishEvent(new SituationParticipantRemovedEvent(
                    situationId, userId, SituationParticipantRemovalReason.PARTICIPANT_LEFT, participant.getLeftAt()));
        }
        return new SituationLeaveResponse(situationId, userId, participant.getStatus(), participant.getLeftAt());
    }

    private SituationParticipant selectParticipant(List<SituationParticipant> history) {
        List<SituationParticipant> active = history.stream()
                .filter(participant -> participant.getStatus() == SituationParticipantStatus.ACTIVE).toList();
        if (active.size() > 1) {
            throw new DataIntegrityViolationException("동일 상황·사용자의 ACTIVE 참여 행이 여러 개입니다.");
        }
        if (!active.isEmpty()) {
            return active.getFirst();
        }
        return history.stream().max(Comparator.comparing(SituationParticipant::getJoinedAt)
                        .thenComparing(SituationParticipant::getId))
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
    }
}
