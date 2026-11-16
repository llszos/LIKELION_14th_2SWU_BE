package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.exception.CareRequestErrorCode;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeEventRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeProposalRepository;
import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
import com.gyeot.global.config.JpaAuditingConfig;
import com.gyeot.global.exception.BusinessException;
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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대상자의 수락과 요청자의 취소가 동시에 들어와도 결과가 어긋나지 않는지 확인합니다.
 * 서비스가 달라도(CareRequestResponseService, CareRequestStatusService) 같은 요청 행을 잠그므로 차례로 처리됩니다.
 * 트랜잭션 설정은 CareRequestResponseConcurrencyTest와 같습니다. (서비스마다 진짜 커밋이 필요)
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAuditingConfig.class,
        SafetyInformationCipher.class,
        CareRequestFinder.class,
        CareRequestLifecycle.class,
        CareRequestResponseService.class,
        CareRequestStatusService.class
})
@ActiveProfiles("test")
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class CareRequestStatusConcurrencyTest {
    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 9, 0);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            ZoneId seoul = ZoneId.of("Asia/Seoul");
            return Clock.fixed(NOW.atZone(seoul).toInstant(), seoul);
        }
    }

    private static final Long REQUESTER_ID = 10L;
    private static final Long TARGET_ID = 20L;

    @Autowired private CareRequestResponseService responseService;
    @Autowired private CareRequestStatusService statusService;
    @Autowired private CareRequestRepository careRequestRepository;
    @Autowired private RequestAssignmentRepository assignmentRepository;
    @Autowired private RequestChangeProposalRepository proposalRepository;
    @Autowired private RequestChangeEventRepository eventRepository;

    @AfterEach
    void cleanUp() {
        eventRepository.deleteAllInBatch();
        proposalRepository.deleteAllInBatch();
        assignmentRepository.deleteAllInBatch();
        careRequestRepository.deleteAllInBatch();
    }

    @Test
    void 수락과_취소가_동시에_들어와도_결과는_항상_취소이고_지정은_마감된다() throws Exception {
        CareRequest request = careRequestRepository.save(CareRequest.createGeneral(1L, REQUESTER_ID, HelpType.HOSPITAL,
                "병원 동행 부탁드려요", "○○병원", NOW.plusDays(1), NOW.plusDays(1).plusHours(3), null));
        assignmentRepository.save(RequestAssignment.assign(request, TARGET_ID, 1, NOW.plusHours(12)));
        Long requestId = request.getId();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<String> accept = executor.submit(gate(ready, start,
                () -> responseService.respond(requestId, TARGET_ID, AssignmentResponseStatus.AVAILABLE)));
        Future<String> cancel = executor.submit(gate(ready, start,
                () -> statusService.cancel(requestId, REQUESTER_ID)));
        ready.await();
        start.countDown();
        String acceptResult = accept.get();
        String cancelResult = cancel.get();
        executor.shutdown();

        // 순서는 그때그때 다르지만 가능한 결과는 둘뿐
        // - 수락 먼저: 수락 성공 → 취소 성공 (수락된 요청도 취소 가능)
        // - 취소 먼저: 취소 성공 → 수락은 이미 끝난 요청이라 409
        assertThat(cancelResult).isEqualTo("SUCCESS");
        assertThat(acceptResult).isIn("SUCCESS", CareRequestErrorCode.REQUEST_ALREADY_ENDED.getCode());

        // 어느 순서든 최종 상태는 같음: 취소됨 + 지정 마감
        assertThat(careRequestRepository.findById(requestId).orElseThrow().getStatus())
                .isEqualTo(CareRequestStatus.CANCELED);
        List<RequestAssignment> assignments = assignmentRepository.findAllByCareRequestIdOrderByAssignmentOrderAsc(requestId);
        assertThat(assignments).singleElement()
                .extracting(RequestAssignment::getCloseReason).isEqualTo(AssignmentCloseReason.REQUEST_ENDED);
    }

    /** 출발 신호를 기다렸다가 실행하고,
     * 성공이면 "SUCCESS", 업무 예외면 에러 코드를 돌려줌
     */
    private Callable<String> gate(CountDownLatch ready, CountDownLatch start, Runnable action) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                action.run();
                return "SUCCESS";
            } catch (BusinessException e) {
                return e.getErrorCode().getCode();
            }
        };
    }
}
