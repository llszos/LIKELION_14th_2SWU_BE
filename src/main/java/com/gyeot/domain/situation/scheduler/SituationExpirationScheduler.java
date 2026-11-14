package com.gyeot.domain.situation.scheduler;

import com.gyeot.domain.situation.service.SituationExpirationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SituationExpirationScheduler {
    private final SituationExpirationService service;

    /** 공통 SchedulingConfig가 실행을 관리합니다. 여기에는 배치 전체 트랜잭션을 두지 않습니다. */
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    public void expireSituations() {
        int ended = 0;
        int failed = 0;
        long afterId = 0L;
        while (true) {
            var candidates = service.findCandidates(afterId);
            if (candidates.isEmpty()) {
                break;
            }
            for (Long id : candidates) {
                // 성공 여부와 무관하게 전진합니다. 실패 대상은 다음 실행에서 재시도합니다.
                afterId = id;
                try {
                    if (service.expire(id)) {
                        ended++;
                    }
                } catch (RuntimeException exception) {
                    failed++;
                    log.warn("상황 자동 종료 실패: situationId={}, 원인={}", id, exception.getMessage(), exception);
                }
            }
        }
        log.info("상황 자동 종료 완료: 종료 {}건, 실패 {}건", ended, failed);
    }
}
