package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.entity.SituationStatus;
import com.gyeot.domain.situation.repository.SituationEndRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SituationExpirationService {
    private static final int BATCH_SIZE = 100;
    private final SituationEndRepository repository;
    private final SituationEndProcessor processor;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<Long> findCandidates(long afterId) {
        return repository.findExpiredIds(now(), afterId, PageRequest.of(0, BATCH_SIZE));
    }

    /** 후보 조회의 스냅샷을 신뢰하지 않고, 대상별 새 트랜잭션과 잠금으로 재검증합니다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expire(Long situationId) {
        var situation = repository.findByIdForUpdate(situationId).orElse(null);
        // [startAt, endAt): endAt과 같아지는 순간부터 자동 종료 대상입니다.
        if (situation == null || situation.getStatus() != SituationStatus.ACTIVE
                || situation.getEndAt().isAfter(now())) {
            return false;
        }
        return processor.endIfActive(situation);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")));
    }
}
