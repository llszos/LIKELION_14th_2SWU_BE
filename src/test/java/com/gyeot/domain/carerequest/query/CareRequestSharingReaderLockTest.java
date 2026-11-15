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

import java.time.LocalDateTime;
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
            .withEnv("TZ", "Asia/Seoul");

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 9, 0);
    private static final Long REQUESTER_ID = 10L;
    private static final Long TARGET_ID = 20L;

    @Autowired private CareRequestSharingReader reader;
    @Autowired private CareRequestRepository careRequestRepository;
    @Autowired private RequestAssignmentRepository assignmentRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @AfterEach
    void tearDown() {
        assignmentRepository.deleteAllInBatch();
        careRequestRepository.deleteAllInBatch();
    }

    @Test
    void 먼저_잠근_트랜잭션이_취소하고_끝날_때까지_기다린_뒤_취소된_값을_받는다() throws Exception {
        Long requestId = acceptedRequest();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            // 1. 첫 번째 트랜잭션: 잠그고 → 신호를 받을 때까지 기다렸다가 → 요청 취소·지정 마감 후 커밋
            //    (C의 요청 취소도 요청 행을 먼저 잠그므로, 여기서는 같은 잠금으로 그 상황을 흉내 냈습니다.)
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

            // 2. 두 번째 트랜잭션(D의 다시 허용): 같은 요청을 잠그려고 하면 기다려야 함
            CompletableFuture<SharingRequestInfo> second = CompletableFuture.supplyAsync(
                    () -> tx.execute(status -> reader.findByIdForUpdate(requestId).orElseThrow()), executor);
            Thread.sleep(500);
            assertThat(second).isNotDone(); // 잠금을 기다리는 중

            // 3. 첫 번째가 커밋하면 두 번째가 잠금을 얻고, 취소된 최신 값을 받음
            releaseFirst.countDown();
            first.get(10, TimeUnit.SECONDS);
            SharingRequestInfo info = second.get(10, TimeUnit.SECONDS);

            assertThat(info.status()).isEqualTo(CareRequestStatus.CANCELED);
            assertThat(info.assignmentId()).isNull();
            assertThat(info.assigneeId()).isNull();
            assertThat(info.acceptedAt()).isNull();
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void 트랜잭션_없이_부르면_잠그지_않고_예외가_난다() {
        Long requestId = acceptedRequest();

        assertThatThrownBy(() -> reader.findByIdForUpdate(requestId))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // === 준비 코드 ===

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

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
