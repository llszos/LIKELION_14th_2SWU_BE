package com.gyeot.domain.access.scheduler;

import com.gyeot.domain.access.entity.PermissionStatus;
import com.gyeot.domain.access.repository.InformationAccessLogRepository;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 보존 기간(2년)이 지난 접근 이력과 회수된 권한을 하루 1회 정리합니다. (F-10, ERD 4.11·4.12, #44)
 *
 * 처리 순서 (ERD 4.11)
 * 1. occurred_at이 기준 시각보다 이전인 접근 이력 삭제
 * 2. 3번에서 삭제할 권한을 가리키는 남은 이력의 permission_id를 NULL로 변경 (FK 미생성이라 코드에서 처리)
 *    권한의 이력은 회수 시각 이전에 쌓여 보통 1번에서 함께 지워지므로, 로그의 연결 해제 건수는 대부분 0건입니다.
 * 3. revoked_at이 기준 시각보다 이전인 REVOKED 권한 삭제
 *
 * - 기준 시각 = 실행 시각 - 2년. 정확히 기준 시각인 데이터는 남깁니다.
 * - 세 단계는 한 트랜잭션입니다. 중간에 실패하면 앞 단계도 모두 되돌립니다.
 * - 같은 기준 시각으로 다시 실행해도 추가 변경이 없습니다.
 * - 접근 이력은 이 스케줄러 외에는 수정·삭제하지 않습니다. (F-10)
 * - 급한 요청(F-13) 24시간 권한 만료는 SHOULD 범위라 여기서 다루지 않습니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccessRetentionScheduler {

    static final long RETENTION_YEARS = 2; // 보존 기간 (F-10)

    private final InformationAccessLogRepository accessLogRepository;
    private final RequestInformationPermissionRepository permissionRepository;
    private final Clock clock;

    /**
     * 매일 03:30(한국 시간)에 실행합니다. Refresh Token 정리(03:00)와 시각을 나눴습니다.
     * 테스트에서는 이 메서드를 직접 호출해 확인합니다.
     */
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Seoul")
    @Transactional
    public void deleteExpired() {

        // 기준 시각은 실행마다 한 번만 구해 세 단계에 같은 값을 씁니다. DB가 마이크로초까지 저장해 맞춰 자릅니다.
        LocalDateTime cutoff = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS).minusYears(RETENTION_YEARS);

        int deletedLogs = accessLogRepository.deleteAllOccurredBefore(cutoff);
        int unlinkedLogs = accessLogRepository.clearPermissionIdOfRevokedBefore(PermissionStatus.REVOKED, cutoff);
        int deletedPermissions = permissionRepository.deleteAllRevokedBefore(PermissionStatus.REVOKED, cutoff);

        // 처리 건수만 남깁니다. (개인정보·민감정보 제외, SchedulingConfig 규칙)
        log.info("보존 기간 정리 완료: 접근 이력 삭제 {}건, 이력 권한 연결 해제 {}건, 회수 권한 삭제 {}건",
                deletedLogs, unlinkedLogs, deletedPermissions);
    }
}
