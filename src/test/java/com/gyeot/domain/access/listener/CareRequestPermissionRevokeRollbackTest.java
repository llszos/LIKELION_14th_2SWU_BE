package com.gyeot.domain.access.listener;

import com.gyeot.domain.access.entity.PermissionStatus;
import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.repository.InformationAccessLogRepository;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.carerequest.event.CareRequestCompletedEvent;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import com.gyeot.domain.safetycard.repository.SafetyInformationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * 회수 처리 중 이력 저장이 실패하면 권한 상태와 발행한 쪽의 변경까지 모두 되돌아가는지 확인합니다. (#43)
 *
 * - 접근 이력 저장소를 실패하는 가짜로 바꿔야 해서, 다른 테스트에 영향을 주지 않도록 클래스를 따로 두었습니다.
 *   (CareRequestPermissionListenerIntegrationTest의 롤백 테스트는 권한 생성 실패 경로입니다.)
 * - 권한은 C 이벤트 없이 저장소로 바로 ACTIVE 상태를 만들어 둡니다. 부여 때 이력을 저장하지 않으려는 것입니다.
 * - C 변경 대신, 이벤트를 발행하는 트랜잭션에서 안심카드 항목 하나를 함께 저장해 롤백 여부를 봅니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class CareRequestPermissionRevokeRollbackTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    private static final Long REQUEST_ID = 1L;
    private static final Long OWNER_ID = 10L;   // 요청자 = 정보 주인
    private static final Long TARGET_ID = 20L;  // 담당자
    private static final Long ASSIGNMENT_ID = 7L;
    private static final LocalDateTime GRANTED_AT = LocalDateTime.of(2026, 11, 13, 10, 0);
    private static final LocalDateTime ENDED_AT = GRANTED_AT.plusHours(3);

    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private SafetyInformationRepository safetyInformationRepository;
    @Autowired private RequestInformationPermissionRepository permissionRepository;

    @MockitoBean private InformationAccessLogRepository accessLogRepository; // 회수 이력 저장을 실패시킴

    private Long permissionId;

    @BeforeEach
    void setUp() {
        Long allergyId = safetyInformationRepository.save(
                SafetyInformation.create(OWNER_ID, InformationType.ALLERGY, "땅콩", "호흡 곤란")).getId();

        // 수락된(ACTIVE) 권한: 회수되면 AUTO_REVOKED 이력을 저장하려다 실패하게 됨
        RequestInformationPermission permission =
                RequestInformationPermission.createPending(REQUEST_ID, ASSIGNMENT_ID, allergyId, InformationType.ALLERGY);
        permission.activate(GRANTED_AT, null);
        permissionId = permissionRepository.save(permission).getId();

        when(accessLogRepository.saveAll(anyList())).thenThrow(new IllegalStateException("이력 저장 실패"));
    }

    @AfterEach
    void cleanUp() {
        permissionRepository.deleteAll();
        safetyInformationRepository.deleteAll();
    }

    @Test
    void 회수_이력_저장이_실패하면_권한_상태와_같은_트랜잭션의_변경이_함께_롤백된다() {
        long itemsBefore = safetyInformationRepository.count();

        // C 변경 대신 같은 트랜잭션에서 항목 하나를 저장한 뒤 완료 이벤트 발행 → 회수 중 이력 저장 실패
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            safetyInformationRepository.save(SafetyInformation.create(OWNER_ID, InformationType.CAUTION, "주의", "내용"));
            eventPublisher.publishEvent(new CareRequestCompletedEvent(
                    REQUEST_ID, OWNER_ID, ASSIGNMENT_ID, TARGET_ID, TARGET_ID, ENDED_AT));
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("이력 저장 실패");

        // 발행한 쪽의 변경(항목 저장)이 되돌려짐
        assertThat(safetyInformationRepository.count()).isEqualTo(itemsBefore);

        // 권한도 회수 전 그대로 (REVOKED로 바뀐 상태가 저장되지 않음)
        RequestInformationPermission permission = permissionRepository.findById(permissionId).orElseThrow();
        assertThat(permission.getStatus()).isEqualTo(PermissionStatus.ACTIVE);
        assertThat(permission.getRevokeReason()).isNull();
        assertThat(permission.getRevokedAt()).isNull();
    }
}
