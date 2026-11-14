package com.gyeot.domain.safetycard.service;

import com.gyeot.domain.access.entity.AccessEventType;
import com.gyeot.domain.access.entity.InformationAccessLog;
import com.gyeot.domain.access.entity.PermissionStatus;
import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.access.repository.InformationAccessLogRepository;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.RequestType;
import com.gyeot.domain.carerequest.event.CareRequestAcceptedEvent;
import com.gyeot.domain.carerequest.event.CareRequestCompletedEvent;
import com.gyeot.domain.carerequest.event.CareRequestCreatedEvent;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader.SharingRequestInfo;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import com.gyeot.domain.safetycard.exception.SafetyCardErrorCode;
import com.gyeot.domain.safetycard.repository.SafetyInformationRepository;
import com.gyeot.global.exception.BusinessException;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * 항목 삭제와 권한 변경이 실제 MySQL에서 항목 행 잠금으로 순서대로 처리되는지 확인합니다. (#38 잠금 규칙)
 *
 * - 트랜잭션 두 개를 스레드 두 개로 동시에 엽니다. 먼저 시작한 쪽(선행)이 항목 행을 잠근 채 멈추고,
 *   나중 쪽(후행)이 같은 항목 잠금에서 기다리는지, 선행이 커밋한 뒤 후행이 최신 상태로 처리하는지 봅니다.
 * - 후행이 시작한 뒤에도 선행이 멈춘 동안 끝나지 않는지 보고, 최종 데이터로 "선행 커밋 뒤 최신 상태로 처리함"을 확인합니다.
 *   일정 시간 동안 끝나지 않았다는 것만으로 DB 잠금 대기라고 확정할 수는 없어서, 최종 데이터 검증과 함께 판단합니다.
 *   잠금이 없다면 후행이 선행보다 먼저 끝나거나, 최종 데이터가 아래 기대와 달라집니다.
 * - 경로마다 양쪽 순서를 모두 봅니다. (삭제 먼저 / 권한 변경 먼저)
 * - C 이벤트는 C 대신 테스트가 트랜잭션 안에서 직접 발행합니다. C의 공유용 요청 조회는 가짜로 바꿉니다.
 * - 각 테스트는 커밋되므로 끝나면 직접 지웁니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class SafetyInformationDeleteConcurrencyTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    private static final Long REQUEST_ID = 1L;
    private static final Long OWNER_ID = 10L;     // 요청자 = 정보 주인
    private static final Long TARGET_ID = 20L;    // 대상자, 수락 후 담당자
    private static final Long ASSIGNMENT_ID = 7L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 11, 13, 10, 0);
    private static final LocalDateTime ACCEPTED_AT = CREATED_AT.plusHours(1);
    private static final LocalDateTime ENDED_AT = CREATED_AT.plusHours(2);

    // 후행이 선행의 잠금에서 기다리는지 볼 시간 (이 시간 안에 끝나면 잠금 없이 지나간 것)
    private static final long BLOCK_CHECK_MILLIS = 700;

    @Autowired private SafetyInformationService safetyInformationService;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private SafetyInformationRepository safetyInformationRepository;
    @Autowired private RequestInformationPermissionRepository permissionRepository;
    @Autowired private InformationAccessLogRepository accessLogRepository;

    @MockitoBean private CareRequestSharingReader careRequestSharingReader;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private Long allergyId;

    @BeforeEach
    void setUp() {
        allergyId = safetyInformationRepository.save(
                SafetyInformation.create(OWNER_ID, InformationType.ALLERGY, "땅콩", "호흡 곤란")).getId();

        // 항목 삭제 알림용 C 조회: 요청 1번의 현재 지정은 7번, 담당자는 20번
        when(careRequestSharingReader.findAllByIds(anyCollection()))
                .thenReturn(Map.of(REQUEST_ID, new SharingRequestInfo(REQUEST_ID, OWNER_ID, ASSIGNMENT_ID,
                        CareRequestStatus.ACCEPTED, RequestType.GENERAL, null, null, null, TARGET_ID, ACCEPTED_AT,
                        HelpType.HOSPITAL)));
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        // 스레드가 끝나 잠금이 모두 풀린 뒤에 지움 (남은 트랜잭션이 행을 잡고 있으면 deleteAll이 막힘)
        executor.shutdown();
        if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
                    .as("DB 정리 전에 작업 스레드가 종료돼야 합니다")
                    .isTrue();
        }
        accessLogRepository.deleteAll();
        permissionRepository.deleteAll();
        safetyInformationRepository.deleteAll();
    }

    // === 새 권한 생성 ↔ 삭제 ===

    @Test
    void 삭제가_먼저_잠그면_요청_생성은_기다렸다가_삭제된_항목이라_거부된다() throws Exception {
        // 선행: 삭제 (항목 잠금을 쥔 채 멈춤)
        // 후행: 요청 생성 (같은 항목 잠금에서 기다림 → 삭제 커밋 뒤 항목이 없어 전체 거부)
        Future<?> creation = runWhileFirstHoldsLock(
                () -> safetyInformationService.delete(OWNER_ID, allergyId),
                () -> publish(created()));

        assertThatThrownBy(() -> creation.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(SafetyCardErrorCode.SAFETY_INFO_INVALID_SELECTION);

        assertThat(safetyInformationRepository.findById(allergyId)).isEmpty();
        assertThat(permissionRepository.findAll()).isEmpty(); // 사라진 항목을 가리키는 권한이 생기지 않음
    }

    @Test
    void 요청_생성이_먼저_잠그면_삭제는_기다렸다가_새_권한까지_회수한다() throws Exception {
        // 선행: 요청 생성 (항목 잠금을 쥔 채 멈춘 뒤 PENDING 생성)
        // 후행: 삭제 (같은 항목 잠금에서 기다림 → 생성 커밋 뒤 새 권한을 보고 회수)
        Future<?> deletion = runWhileFirstHoldsLock(
                () -> publish(created()),
                () -> safetyInformationService.delete(OWNER_ID, allergyId));

        deletion.get(10, TimeUnit.SECONDS); // 예외 없이 끝남

        assertThat(safetyInformationRepository.findById(allergyId)).isEmpty();
        assertThat(permissionRepository.findAll()).singleElement().satisfies(permission -> {
            assertThat(permission.getStatus()).isEqualTo(PermissionStatus.REVOKED);
            assertThat(permission.getRevokeReason()).isEqualTo(RevokeReason.ITEM_DELETED);
            assertThat(permission.getInformationId()).isNull(); // 삭제된 항목을 가리키지 않음
        });
    }

    // === 기존 권한 활성화 ↔ 삭제 ===

    @Test
    void 삭제가_먼저_잠그면_수락은_기다렸다가_회수된_권한을_활성화하지_않는다() throws Exception {
        publish(created()); // PENDING 권한 준비

        // 선행: 삭제, 후행: 수락 (삭제 커밋 뒤 잠금 조회로 REVOKED를 보고 건너뜀)
        Future<?> acceptance = runWhileFirstHoldsLock(
                () -> safetyInformationService.delete(OWNER_ID, allergyId),
                () -> publish(accepted()));

        acceptance.get(10, TimeUnit.SECONDS);

        assertThat(permissionRepository.findAll()).singleElement().satisfies(permission -> {
            assertThat(permission.getStatus()).isEqualTo(PermissionStatus.REVOKED);      // 다시 ACTIVE로 덮어쓰지 않음
            assertThat(permission.getRevokeReason()).isEqualTo(RevokeReason.ITEM_DELETED);
            assertThat(permission.getGrantedAt()).isNull();                             // 활성화된 적 없음
        });
        assertThat(accessLogRepository.findAll()).isEmpty(); // 부여 이력도, PENDING 회수 이력도 없음
    }

    @Test
    void 수락이_먼저_잠그면_삭제는_기다렸다가_활성화된_권한을_회수하고_이력을_남긴다() throws Exception {
        publish(created()); // PENDING 권한 준비

        // 선행: 수락 (ACTIVE + GRANTED), 후행: 삭제 (수락 커밋 뒤 ACTIVE를 보고 회수 + AUTO_REVOKED)
        Future<?> deletion = runWhileFirstHoldsLock(
                () -> publish(accepted()),
                () -> safetyInformationService.delete(OWNER_ID, allergyId));

        deletion.get(10, TimeUnit.SECONDS);

        assertThat(permissionRepository.findAll()).singleElement().satisfies(permission -> {
            assertThat(permission.getStatus()).isEqualTo(PermissionStatus.REVOKED);
            assertThat(permission.getRevokeReason()).isEqualTo(RevokeReason.ITEM_DELETED);
            assertThat(permission.getGrantedAt()).isEqualTo(ACCEPTED_AT); // 수락은 반영된 뒤 회수됨
        });
        assertThat(accessLogRepository.findAll()).extracting(InformationAccessLog::getEventType)
                .containsExactlyInAnyOrder(AccessEventType.GRANTED, AccessEventType.AUTO_REVOKED);
    }

    // === 기존 권한 회수 ↔ 삭제 ===

    @Test
    void 삭제가_먼저_잠그면_완료_회수는_기다렸다가_사유를_덮어쓰지_않는다() throws Exception {
        publish(created());
        publish(accepted()); // ACTIVE 권한 준비

        // 선행: 삭제 (ITEM_DELETED), 후행: 요청 완료 (삭제 커밋 뒤 REVOKED를 보고 건너뜀)
        Future<?> completion = runWhileFirstHoldsLock(
                () -> safetyInformationService.delete(OWNER_ID, allergyId),
                () -> publish(new CareRequestCompletedEvent(REQUEST_ID, OWNER_ID, ASSIGNMENT_ID, TARGET_ID,
                        TARGET_ID, ENDED_AT)));

        completion.get(10, TimeUnit.SECONDS);

        assertThat(permissionRepository.findAll()).singleElement()
                .extracting(RequestInformationPermission::getRevokeReason)
                .isEqualTo(RevokeReason.ITEM_DELETED); // REQUEST_ENDED로 덮어쓰지 않음

        // 회수 이력은 삭제 때 1건만 (완료가 한 번 더 남기지 않음)
        assertThat(accessLogRepository.findAll()).filteredOn(log -> log.getEventType() == AccessEventType.AUTO_REVOKED)
                .hasSize(1);
    }

    @Test
    void 완료_회수가_먼저_잠그면_삭제는_기다렸다가_처음_회수_사유를_유지한다() throws Exception {
        publish(created());
        publish(accepted()); // ACTIVE 권한 준비

        // 선행: 요청 완료 (REQUEST_ENDED + AUTO_REVOKED), 후행: 삭제 (완료 커밋 뒤 열린 권한이 없어 회수하지 않음)
        Future<?> deletion = runWhileFirstHoldsLock(
                () -> publish(new CareRequestCompletedEvent(REQUEST_ID, OWNER_ID, ASSIGNMENT_ID, TARGET_ID,
                        TARGET_ID, ENDED_AT)),
                () -> safetyInformationService.delete(OWNER_ID, allergyId));

        deletion.get(10, TimeUnit.SECONDS);

        assertThat(safetyInformationRepository.findById(allergyId)).isEmpty();
        assertThat(permissionRepository.findAll()).singleElement().satisfies(permission -> {
            assertThat(permission.getStatus()).isEqualTo(PermissionStatus.REVOKED);
            assertThat(permission.getRevokeReason()).isEqualTo(RevokeReason.REQUEST_ENDED); // ITEM_DELETED로 덮어쓰지 않음
            assertThat(permission.getRevokedAt()).isEqualTo(ENDED_AT);                    // 처음 회수 시각 유지
            assertThat(permission.getInformationId()).isNull();                           // 항목 참조는 삭제로 비워짐
        });

        // 회수 이력은 완료 때 1건만 (삭제가 한 번 더 남기지 않음)
        assertThat(accessLogRepository.findAll()).filteredOn(log -> log.getEventType() == AccessEventType.AUTO_REVOKED)
                .hasSize(1);
    }

    // === 도우미 ===

    /**
     * 선행 작업이 항목 행 잠금을 쥔 채 멈춘 동안 후행 작업을 시작해, 후행이 끝나지 않는지 확인한 뒤 선행을 끝냅니다.
     *
     * 1. 선행 스레드: 트랜잭션을 열고 항목 행을 잠근 뒤 신호를 보내고, 풀어 줄 때까지 멈춤
     * 2. 후행 스레드: 선행의 신호를 받은 뒤 시작하고, 시작했다는 신호를 보냄
     * 3. 후행 시작 신호를 받은 뒤 일정 시간 동안 후행이 끝나지 않는지 확인
     *    (스레드가 아직 실행되지 않아 끝나지 않은 경우를 빼려고 시작 신호 뒤부터 잼)
     *    이것만으로 DB 잠금 대기라고 확정하지는 않고, 테스트마다 최종 데이터로 순서를 함께 확인함
     * 4. 선행을 풀어 주면 선행 작업을 마저 하고 커밋, 그 뒤 후행이 이어서 실행됨
     *
     * - 선행 작업(서비스 호출, 이벤트 발행)은 이 트랜잭션에 참여하므로, 미리 잡은 잠금을 그대로 이어서 씁니다.
     * - 확인이 실패해도 finally에서 선행을 반드시 풀어 줍니다. 잠금이 남으면 정리(deleteAll)까지 막혀 원인이 가려집니다.
     *
     * @return 후행 작업의 결과 (예외 확인용)
     */
    private Future<?> runWhileFirstHoldsLock(Runnable first, Runnable second) throws Exception {
        CountDownLatch firstLocked = new CountDownLatch(1);   // 선행이 잠금을 잡았다는 신호
        CountDownLatch secondStarted = new CountDownLatch(1); // 후행 스레드가 실행을 시작했다는 신호
        CountDownLatch releaseFirst = new CountDownLatch(1);  // 선행을 계속하게 하는 신호

        Future<?> firstResult = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            safetyInformationRepository.findByIdForUpdate(allergyId); // 항목 행 잠금 (실제 작업이 처음 잡는 잠금과 같음)
            firstLocked.countDown();
            await(releaseFirst);
            first.run(); // 같은 트랜잭션이라 이미 잡은 잠금을 기다리지 않고 이어서 씀
        }));

        try {
            assertThat(firstLocked.await(10, TimeUnit.SECONDS)).as("선행 작업이 항목 잠금을 잡아야 합니다").isTrue();

            Future<?> secondResult = executor.submit(() -> {
                secondStarted.countDown(); // 실행 시작 (이 직후 같은 항목 잠금을 요청함)
                second.run();
            });

            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).as("후행 작업이 시작돼야 합니다").isTrue();

            // 선행이 잠금을 쥐고 있는 동안 후행은 끝나지 않아야 함
            Thread.sleep(BLOCK_CHECK_MILLIS);
            assertThat(secondResult.isDone()).as("후행 작업이 선행 커밋 전에 끝나면 안 됩니다").isFalse();

            releaseFirst.countDown();
            firstResult.get(10, TimeUnit.SECONDS); // 선행은 정상 커밋
            return secondResult;
        } finally {
            releaseFirst.countDown(); // 위 확인이 실패해도 선행 트랜잭션을 끝내 잠금을 풀어 줌 (이미 0이면 아무 일 없음)
        }
    }

    /** C처럼 트랜잭션 안에서 이벤트를 발행하고 커밋합니다. */
    private void publish(Object event) {
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("선행 작업을 풀어 주는 신호가 오지 않았습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private CareRequestCreatedEvent created() {
        return new CareRequestCreatedEvent(REQUEST_ID, OWNER_ID, RequestType.GENERAL,
                ASSIGNMENT_ID, TARGET_ID, List.of(allergyId), CREATED_AT);
    }

    private static CareRequestAcceptedEvent accepted() {
        return new CareRequestAcceptedEvent(REQUEST_ID, OWNER_ID, RequestType.GENERAL, ASSIGNMENT_ID, TARGET_ID, ACCEPTED_AT);
    }
}
