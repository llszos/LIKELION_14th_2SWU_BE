package com.gyeot.domain.carerequest.query;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import com.gyeot.domain.carerequest.enums.CancelReason;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader.SharingRequestInfo;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
import com.gyeot.global.config.JpaAuditingConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * findByIdForUpdate(D #46)가 실제로 요청 행을 잠그는지, 잠금을 얻은 뒤 최신 값을 돌려주는지 확인합니다.
 * 잠금은 트랜잭션이 끝나야 풀리므로, 테스트 메서드를 트랜잭션 밖에서 실행하고(NOT_SUPPORTED)
 * 트랜잭션 두 개를 각각 다른 스레드에서 실제로 커밋합니다. 만든 데이터는 테스트마다 지웁니다.
 *
 * 잠금 대기 여부는 MySQL의 performance_schema.data_lock_waits(잠금을 기다리는 요청 목록)로 확인합니다.
 * (information_schema.innodb_trx는 일반 조회를 먼저 한 트랜잭션의 잠금 대기를 LOCK WAIT로 보여 주지 않는 경우가 있어 쓰지 않음)
 * performance_schema를 읽으려면 권한이 필요해 이 테스트만 root 계정으로 연결합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, SafetyInformationCipher.class, CareRequestSharingReaderImpl.class})
@ActiveProfiles("test")
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CareRequestSharingReaderLockTest {
    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withUsername("root") // 잠금 대기 확인(performance_schema.data_lock_waits)에 권한이 필요
            .withPassword("test")
            .withEnv("TZ", "Asia/Seoul");

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 9, 0);
    private static final Long REQUESTER_ID = 10L;
    private static final Long TARGET_ID = 20L;

    @Autowired private CareRequestSharingReader reader;
    @Autowired private CareRequestRepository careRequestRepository;
    @Autowired private RequestAssignmentRepository assignmentRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;

    @AfterEach
    void tearDown() {
        assignmentRepository.deleteAllInBatch();
        careRequestRepository.deleteAllInBatch();
    }

    @Test
    void 먼저_잠근_트랜잭션이_취소하고_끝날_때까지_기다린_뒤_반환값과_이후_일반_조회_모두_최신_값이다() throws Exception {
        Long requestId = acceptedRequest();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            // 1. 첫 번째 트랜잭션: 잠그고 → 신호를 받을 때까지 기다렸다가 → 요청 취소·지정 마감 후 커밋
            //    (C의 요청 취소도 요청 행을 먼저 잠그므로, 여기서는 같은 잠금으로 그 상황을 흉내 냄)
            CompletableFuture<Void> first = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
                assertThat(reader.findByIdForUpdate(requestId).orElseThrow().status())
                        .isEqualTo(CareRequestStatus.ACCEPTED);
                firstLocked.countDown();
                await(releaseFirst);
                CareRequest request = careRequestRepository.findById(requestId).orElseThrow();
                RequestAssignment assignment = assignmentRepository.findByCareRequestIdAndClosedAtIsNull(requestId)
                        .orElseThrow();
                assignment.close(AssignmentCloseReason.REQUEST_ENDED, NOW);
                request.cancel(CancelReason.REQUESTER_CANCELED, NOW);
            }), executor);
            assertThat(firstLocked.await(10, TimeUnit.SECONDS)).isTrue();

            // 2. 두 번째 트랜잭션(D의 다시 허용·직접 철회): 다른 조회 없이 바로 잠금 조회 → 이어서 일반 조회
            //    D는 요청 잠금 뒤의 첫 일반 조회에서 스냅샷이 만들어진다는 전제로 권한 ID 등을 일반 조회함
            CompletableFuture<SecondResult> second = CompletableFuture.supplyAsync(() -> tx.execute(status -> {
                SharingRequestInfo info = reader.findByIdForUpdate(requestId).orElseThrow();
                Optional<RequestAssignment> openByPlainRead =
                        assignmentRepository.findByCareRequestIdAndClosedAtIsNull(requestId); // 일반 조회
                return new SecondResult(info, openByPlainRead.isPresent());
            }), executor);

            // 두 번째가 실제로 요청 행 잠금을 기다리는 중인지 MySQL에서 확인 (시간을 재지 않고 상태로 확인)
            awaitLockWait();
            assertThat(second).isNotDone();

            // 3. 첫 번째가 커밋하면 두 번째가 잠금을 얻고, 반환값과 이후 일반 조회 모두 취소된 최신 값을 봄
            releaseFirst.countDown();
            first.get(10, TimeUnit.SECONDS);
            SecondResult result = second.get(10, TimeUnit.SECONDS);

            assertThat(result.info().status()).isEqualTo(CareRequestStatus.CANCELED);
            assertThat(result.info().assignmentId()).isNull();
            assertThat(result.info().assigneeId()).isNull();
            assertThat(result.info().acceptedAt()).isNull();
            // 잠금 전에 일반 조회로 스냅샷이 만들어졌다면 마감 전 지정이 보였을 것 (리뷰 지적 사항 회귀 방지)
            assertThat(result.openAssignmentVisibleByPlainRead()).isFalse();
        } finally {
            releaseFirst.countDown(); // 실패해도 첫 번째 트랜잭션이 끝나도록
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); // DB 정리 전에 작업 종료
        }
    }

    @Test
    void 트랜잭션_없이_부르면_잠그지_않고_예외가_난다() {
        Long requestId = acceptedRequest();

        assertThatThrownBy(() -> reader.findByIdForUpdate(requestId))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // === 준비 코드 ===

    private record SecondResult(SharingRequestInfo info, boolean openAssignmentVisibleByPlainRead) {
    }

    /** 수락된 요청을 만들어 커밋합니다. */
    private Long acceptedRequest() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            CareRequest request = careRequestRepository.save(CareRequest.createGeneral(1L, REQUESTER_ID,
                    HelpType.HOSPITAL, "병원 동행 부탁드려요", "○○병원", NOW.plusDays(1),
                    NOW.plusDays(1).plusHours(3), null));
            RequestAssignment assignment = assignmentRepository.save(
                    RequestAssignment.assign(request, TARGET_ID, 1, NOW.plusHours(12)));
            assignment.respond(AssignmentResponseStatus.AVAILABLE, NOW);
            request.accept();
            return request.getId();
        });
    }

    /** 잠금을 기다리는 트랜잭션이 생길 때까지 확인합니다. (최대 10초) */
    private void awaitLockWait() throws InterruptedException {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject(
                    "select count(*) from performance_schema.data_lock_waits", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("두 번째 트랜잭션이 요청 행 잠금을 기다리지 않았습니다.");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}