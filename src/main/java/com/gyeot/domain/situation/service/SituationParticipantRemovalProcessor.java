package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.entity.SituationParticipant;
import com.gyeot.domain.situation.entity.SituationParticipantRole;
import com.gyeot.domain.situation.entity.SituationParticipantStatus;
import com.gyeot.domain.situation.event.SituationParticipantRemovalReason;
import com.gyeot.domain.situation.event.SituationParticipantRemovedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 검증 완료된 관리 엔티티의 내부 상태 변경 전용입니다. HTTP에는 연결하지 않습니다.
 * 호출자는 같은 쓰기 트랜잭션에서 권한·상황 상태를 검증하고 LockReader로 상황 → 참여 행을
 * 잠근 뒤 이 Bean을 호출해야 합니다. 이 컴포넌트는 조회·잠금·HTTP 오류 정책을 대신하지 않습니다.
 */
@Component
@RequiredArgsConstructor
public class SituationParticipantRemovalProcessor {
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    /** 예외를 삼키지 않으며 호출자와 동일한 트랜잭션에서 변경과 이벤트 발행을 수행합니다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void removeValidated(Long situationId, SituationParticipant participant) {
        Objects.requireNonNull(situationId, "situationId는 필수입니다.");
        Objects.requireNonNull(participant, "participant는 필수입니다.");
        if (!situationId.equals(participant.getSituation().getId())) {
            throw new IllegalArgumentException("다른 상황의 참여 이력입니다.");
        }
        if (participant.getRole() == SituationParticipantRole.OWNER) {
            throw new IllegalStateException("OWNER는 제외할 수 없습니다.");
        }
        if (participant.getStatus() != SituationParticipantStatus.ACTIVE) {
            return; // 내부 상태 변경만 멱등 처리하며 HTTP 성공/오류를 결정하지 않습니다.
        }
        LocalDateTime occurredAt = LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")))
                .truncatedTo(ChronoUnit.MICROS);
        participant.remove(occurredAt);
        publisher.publishEvent(new SituationParticipantRemovedEvent(situationId, participant.getUserId(),
                SituationParticipantRemovalReason.PARTICIPANT_LEFT, participant.getLeftAt()));
    }
}
