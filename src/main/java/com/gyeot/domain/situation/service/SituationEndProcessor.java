package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.entity.SituationStatus;
import com.gyeot.domain.situation.event.SituationEndedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** 직접·자동 종료의 공통 상태 전이. 호출자는 같은 트랜잭션에서 상황 잠금과 자격 검증을 완료해야 합니다. */
@Component
@RequiredArgsConstructor
public class SituationEndProcessor {
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean endIfActive(Situation situation) {
        if (situation.getStatus() != SituationStatus.ACTIVE) {
            return false;
        }
        LocalDateTime endedAt = LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")))
                .truncatedTo(ChronoUnit.MICROS);
        situation.end(endedAt);
        eventPublisher.publishEvent(new SituationEndedEvent(situation.getId(), situation.getEndedAt()));
        return true;
    }
}
