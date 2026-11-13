package com.gyeot.domain.access.scheduler;

import com.gyeot.domain.access.entity.AccessEventType;
import com.gyeot.domain.access.entity.InformationAccessLog;
import com.gyeot.domain.access.entity.PermissionStatus;
import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.access.repository.InformationAccessLogRepository;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.global.config.JpaAuditingConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 보존 기간 정리 중 마지막 단계(권한 삭제)가 실패하면 앞 단계(이력 삭제·연결 해제)도 되돌려지는지 확인합니다. (#44)
 * - 테스트 메서드를 트랜잭션 밖에서 실행해(NOT_SUPPORTED), 스케줄러의 @Transactional이 실제로 커밋·롤백을 결정하게 합니다.
 * - 권한 Repository만 실패하는 가짜로 바꾼 스케줄러를 Bean으로 등록합니다. (Bean이어야 @Transactional이 적용됨)
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, SafetyInformationCipher.class, AccessRetentionSchedulerRollbackTest.FailingSchedulerConfig.class})
@ActiveProfiles("test")
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AccessRetentionSchedulerRollbackTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 3, 30);
    static final LocalDateTime OLD = NOW.minusYears(2).minusDays(1);

    @TestConfiguration
    static class FailingSchedulerConfig {
        @Bean
        AccessRetentionScheduler failingScheduler(InformationAccessLogRepository accessLogRepository) {
            RequestInformationPermissionRepository failingPermissionRepository = mock(RequestInformationPermissionRepository.class);
            when(failingPermissionRepository.deleteAllRevokedBefore(any(), any()))
                    .thenThrow(new IllegalStateException("테스트용 권한 삭제 실패"));
            Clock clock = Clock.fixed(NOW.atZone(SEOUL).toInstant(), SEOUL);
            return new AccessRetentionScheduler(accessLogRepository, failingPermissionRepository, clock);
        }
    }

    @Autowired private AccessRetentionScheduler failingScheduler;
    @Autowired private InformationAccessLogRepository accessLogRepository;
    @Autowired private RequestInformationPermissionRepository permissionRepository;

    @AfterEach
    void cleanUp() {
        accessLogRepository.deleteAll();
        permissionRepository.deleteAll();
    }

    @Test
    void 권한_삭제가_실패하면_이력_삭제와_연결_해제도_롤백된다() {
        RequestInformationPermission oldRevoked = RequestInformationPermission.createActive(
                1L, 1L, 100L, InformationType.ALLERGY, OLD.minusDays(1), null);
        oldRevoked.revoke(RevokeReason.REQUEST_ENDED, OLD);
        oldRevoked = permissionRepository.save(oldRevoked);
        InformationAccessLog oldLog = accessLogRepository.save(InformationAccessLog.create(
                null, 10L, 20L, 100L, InformationType.ALLERGY, AccessEventType.VIEWED, OLD));
        InformationAccessLog linkedLog = accessLogRepository.save(InformationAccessLog.create(
                oldRevoked.getId(), 10L, 20L, 100L, InformationType.ALLERGY, AccessEventType.VIEWED, NOW.minusDays(1)));

        assertThatThrownBy(() -> failingScheduler.deleteExpired())
                .isInstanceOf(IllegalStateException.class);

        assertThat(accessLogRepository.findById(oldLog.getId())).isPresent(); // 1단계 이력 삭제가 되돌려짐
        assertThat(accessLogRepository.findById(linkedLog.getId()))           // 2단계 연결 해제가 되돌려짐
                .get().extracting(InformationAccessLog::getPermissionId).isEqualTo(oldRevoked.getId());
        assertThat(permissionRepository.findById(oldRevoked.getId()))
                .get().extracting(RequestInformationPermission::getStatus).isEqualTo(PermissionStatus.REVOKED);
    }
}
