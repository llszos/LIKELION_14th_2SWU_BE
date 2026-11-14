package com.gyeot.domain.carerequest.scheduler;

import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.ProposalStatus;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeProposalRepository;
import com.gyeot.domain.carerequest.service.CareRequestAutoTransitionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Predicate;

/**
 * 시작, 종료 시각과 재확인 기한에 따라 요청 상태를 자동으로 바꿉니다. (#75, F-08, F-29, ERD 7.3)
 *
 * 1분마다 아래 순서로 처리합니다.
 * - 1. 재확인 기한이 지난 대기 중 수정안 → 무효(DEADLINE_PASSED)
 * - 2. 시작 시각이 된 수락됨 요청 → 진행 중
 * - 3. 시작 시각이 된 요청됨 요청 → 만료
 * - 4. 종료 시각이 된 진행 중 요청 → 완료
 *    - 2번 뒤에 하므로, 서버가 멈췄다 켜져 시작과 종료가 모두 지난 요청도 한 번에 진행 중 → 완료가 됩니다.
 *
 * - 대상은 잠그지 않고 ID만 모으고, 실제 처리는 CareRequestAutoTransitionService가 건마다 새 트랜잭션에서
 *   요청 행을 잠근 뒤 상태를 다시 확인해서 진행합니다. 한 건이 실패해도 로그만 남기고 다음 건을 처리합니다.
 * - 두 번 실행돼도 이미 처리된 건은 건너뛰어 결과가 같습니다. 서버가 1대라 동시 실행 방지는 두지 않습니다. (SchedulingConfig 규칙)
 * - 처리 건수와 요청 ID만 로그에 남깁니다. (개인정보 제외)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CareRequestAutoTransitionScheduler {
    private final CareRequestRepository careRequestRepository;
    private final RequestChangeProposalRepository proposalRepository;
    private final CareRequestAutoTransitionService autoTransitionService;
    private final Clock clock;

    /**
     * 매분 0초(한국 시간)에 실행합니다. 시작, 종료는 분 단위로 잡히므로 최대 1분 늦게 처리됩니다.
     * 테스트에서는 시각을 넣을 수 있는 runAt을 직접 호출합니다.
     */
    @Scheduled(cron = "0 * * * * *", zone = "Asia/Seoul")
    public void run() {
        runAt(LocalDateTime.now(clock));
    }

    /**
     * 기준 시각 하나로 네 단계를 처리합니다. DB가 마이크로초까지 저장해 맞춰 자릅니다.
     */
    void runAt(LocalDateTime time) {
        LocalDateTime now = time.truncatedTo(ChronoUnit.MICROS);

        int invalidated = processEach("재확인 기한 지남",
                proposalRepository.findRequestIdsByStatusAndResponseDeadlineBefore(ProposalStatus.PENDING, now),
                id -> autoTransitionService.invalidateExpiredProposal(id, now));
        int started = processEach("시작",
                careRequestRepository.findIdsByStatusAndStartsAtReached(CareRequestStatus.ACCEPTED, now),
                id -> autoTransitionService.startIfDue(id, now));
        int expired = processEach("만료",
                careRequestRepository.findIdsByStatusAndStartsAtReached(CareRequestStatus.REQUESTED, now),
                id -> autoTransitionService.expireIfDue(id, now));
        int completed = processEach("완료",
                careRequestRepository.findIdsByStatusAndEndsAtReached(CareRequestStatus.IN_PROGRESS, now),
                id -> autoTransitionService.completeIfDue(id, now));

        // 매분 실행이라, 처리한 건이 있을 때만 남깁니다.
        if (invalidated + started + expired + completed > 0) {
            log.info("요청 자동 처리 완료: 수정안 무효 {}건, 시작 {}건, 만료 {}건, 완료 {}건",
                    invalidated, started, expired, completed);
        }
    }

    /**
     * ID마다 처리하고, 실제로 상태가 바뀐 건수를 돌려줍니다. 한 건이 실패해도 다음 건은 계속합니다.
     */
    private int processEach(String stepName, List<Long> requestIds, Predicate<Long> action) {
        int changed = 0;
        for (Long requestId : requestIds) {
            try {
                if (action.test(requestId)) {
                    changed++;
                }
            } catch (RuntimeException exception) {
                log.error("요청 자동 처리({}) 실패: requestId={}", stepName, requestId, exception);
            }
        }
        return changed;
    }
}