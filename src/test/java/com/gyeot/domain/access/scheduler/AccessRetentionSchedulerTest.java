package com.gyeot.domain.access.scheduler;

import com.gyeot.domain.access.entity.AccessEventType;
import com.gyeot.domain.access.entity.InformationAccessLog;
import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.access.repository.InformationAccessLogRepository;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.global.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보존 기간 정리 스케줄러(AccessRetentionScheduler)를 실제 MySQL로 확인합니다. (#44)
 * - 시각은 Clock으로 고정하고, 스케줄 시간을 기다리지 않고 메서드를 직접 호출합니다. (SchedulingConfig 규칙)
 * - 기준 시각 = 2026-11-12 03:30 - 2년 = 2024-11-12 03:30
 * - 각 테스트는 끝나면 롤백됩니다. 중간 실패 롤백은 AccessRetentionSchedulerRollbackTest에서 확인합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // 내장 DB 대신 아래 MySQL 사용
@Import({JpaAuditingConfig.class, SafetyInformationCipher.class, AccessRetentionSchedulerTest.FixedClockConfig.class})
@ActiveProfiles("test")
@Testcontainers
class AccessRetentionSchedulerTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 3, 30);
    static final LocalDateTime CUTOFF = NOW.minusYears(2);
    static final LocalDateTime JUST_BEFORE_CUTOFF = CUTOFF.minusNanos(1_000); // 1마이크로초 전 (DB 정밀도)

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW.atZone(SEOUL).toInstant(), SEOUL);
        }

        @Bean
        AccessRetentionScheduler accessRetentionScheduler(InformationAccessLogRepository accessLogRepository,
                                                          RequestInformationPermissionRepository permissionRepository,
                                                          Clock clock) {
            return new AccessRetentionScheduler(accessLogRepository, permissionRepository, clock);
        }
    }

    @Autowired private AccessRetentionScheduler scheduler;
    @Autowired private InformationAccessLogRepository accessLogRepository;
    @Autowired private RequestInformationPermissionRepository permissionRepository;
    @Autowired private EntityManager entityManager;

    @Test
    void 기준_시각보다_이전인_접근_이력만_삭제된다() {
        InformationAccessLog old = saveLog(null, JUST_BEFORE_CUTOFF);
        InformationAccessLog atCutoff = saveLog(null, CUTOFF);
        InformationAccessLog recent = saveLog(null, NOW.minusDays(1));

        runScheduler();

        assertThat(accessLogRepository.findById(old.getId())).isEmpty();
        assertThat(accessLogRepository.findById(atCutoff.getId())).isPresent(); // 경계 시각은 남김
        assertThat(accessLogRepository.findById(recent.getId())).isPresent();
    }

    @Test
    void 기준_시각보다_이전에_회수된_권한만_삭제된다() {
        RequestInformationPermission oldRevoked = saveRevoked(JUST_BEFORE_CUTOFF);
        RequestInformationPermission revokedAtCutoff = saveRevoked(CUTOFF);
        RequestInformationPermission recentRevoked = saveRevoked(NOW.minusDays(1));
        RequestInformationPermission pending = permissionRepository.save(newPending());
        RequestInformationPermission active = permissionRepository.save(newActive(CUTOFF.minusYears(1)));

        runScheduler();

        assertThat(permissionRepository.findById(oldRevoked.getId())).isEmpty();
        assertThat(permissionRepository.findById(revokedAtCutoff.getId())).isPresent(); // 경계 시각은 남김
        assertThat(permissionRepository.findById(recentRevoked.getId())).isPresent();
        assertThat(permissionRepository.findById(pending.getId())).isPresent();          // 진행 중 권한은 오래돼도 남김
        assertThat(permissionRepository.findById(active.getId())).isPresent();
    }

    @Test
    void 삭제되는_권한을_가리키는_남은_이력은_permission_id만_NULL이_된다() {
        RequestInformationPermission oldRevoked = saveRevoked(JUST_BEFORE_CUTOFF);
        RequestInformationPermission kept = saveRevoked(NOW.minusDays(1));
        // 보통은 이력도 함께 지워지지만, 보존 기간 안의 이력이 남아 있는 경우를 직접 만듭니다.
        InformationAccessLog linkedToDeleted = saveLog(oldRevoked.getId(), NOW.minusDays(1));
        InformationAccessLog linkedToKept = saveLog(kept.getId(), NOW.minusDays(1));

        runScheduler();

        assertThat(accessLogRepository.findById(linkedToDeleted.getId()))
                .get().extracting(InformationAccessLog::getPermissionId).isNull();
        assertThat(accessLogRepository.findById(linkedToKept.getId()))
                .get().extracting(InformationAccessLog::getPermissionId).isEqualTo(kept.getId());
    }

    @Test
    void 두_번_실행해도_결과가_같다() {
        RequestInformationPermission oldRevoked = saveRevoked(JUST_BEFORE_CUTOFF);
        RequestInformationPermission kept = saveRevoked(NOW.minusDays(1));
        InformationAccessLog old = saveLog(null, JUST_BEFORE_CUTOFF);
        InformationAccessLog recent = saveLog(kept.getId(), NOW.minusDays(1));

        runScheduler();
        runScheduler();

        assertThat(accessLogRepository.findById(old.getId())).isEmpty();
        assertThat(permissionRepository.findById(oldRevoked.getId())).isEmpty();
        assertThat(accessLogRepository.findById(recent.getId()))
                .get().extracting(InformationAccessLog::getPermissionId).isEqualTo(kept.getId());
        assertThat(permissionRepository.findById(kept.getId())).isPresent();
    }

    /**
     * 테스트 데이터를 DB에 반영한 뒤 스케줄러를 실행하고, 결과를 DB에서 다시 읽도록 캐시를 비웁니다.
     * 일괄 쿼리(@Modifying)는 영속성 컨텍스트를 거치지 않아, 비우지 않으면 지워진 행이 캐시에서 보일 수 있습니다.
     */
    private void runScheduler() {
        entityManager.flush();
        entityManager.clear();
        scheduler.deleteExpired();
        entityManager.clear();
    }

    private InformationAccessLog saveLog(Long permissionId, LocalDateTime occurredAt) {
        return accessLogRepository.save(InformationAccessLog.create(permissionId, 10L, 20L, 100L,
                InformationType.ALLERGY, AccessEventType.VIEWED, occurredAt));
    }

    private RequestInformationPermission saveRevoked(LocalDateTime revokedAt) {
        RequestInformationPermission permission = newActive(revokedAt.minusDays(1));
        permission.revoke(RevokeReason.REQUEST_ENDED, revokedAt);
        return permissionRepository.save(permission);
    }

    private static RequestInformationPermission newPending() {
        return RequestInformationPermission.createPending(1L, 1L, 100L, InformationType.ALLERGY);
    }

    private static RequestInformationPermission newActive(LocalDateTime grantedAt) {
        return RequestInformationPermission.createActive(1L, 1L, 100L, InformationType.ALLERGY, grantedAt, null);
    }
}
