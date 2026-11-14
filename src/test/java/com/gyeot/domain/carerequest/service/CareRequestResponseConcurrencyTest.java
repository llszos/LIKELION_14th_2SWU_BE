package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.ChangeEventType;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.exception.CareRequestErrorCode;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeEventRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 요청에 수락이 동시에 두 번 들어와도 한 번만 처리되는지 확인합니다. (F-07 중복 수락 방지, 행 잠금)
 *
 * @DataJpaTest는 원래 테스트를 하나의 트랜잭션으로 감싸고 끝나면 롤백해서, 준비한 데이터가 커밋되지 않아 다른 스레드에서 보이지 않습니다.
 * 여기서는 서비스 호출마다 진짜 트랜잭션이 따로 열리고 커밋되어야 하므로 NOT_SUPPORTED로 테스트 전체 트랜잭션을 껐습니다.
 * 대신 @AfterEach에서 직접 지웁니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAuditingConfig.class,
        SafetyInformationCipher.class,
        CareRequestFinder.class,
        CareRequestResponseService.class
})
@ActiveProfiles("test")
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class CareRequestResponseConcurrencyTest {
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

    private static final Long TARGET_ID = 20L;

    @Autowired private CareRequestResponseService service;
    @Autowired private CareRequestRepository careRequestRepository;
    @Autowired private RequestAssignmentRepository assignmentRepository;
    @Autowired private RequestChangeEventRepository eventRepository;

    @AfterEach
    void cleanUp() {
        // 자식 테이블부터 지움 (FK 때문에 요청을 먼저 지울 수 없음)
        eventRepository.deleteAllInBatch();
        assignmentRepository.deleteAllInBatch();
        careRequestRepository.deleteAllInBatch();
    }

    @Test
    void 같은_사람이_동시에_두_번_수락하면_한_번만_성공한다() throws Exception {
        // Repository의 save는 각자 트랜잭션으로 바로 커밋됨
        CareRequest request = careRequestRepository.save(CareRequest.createGeneral(1L, 10L, HelpType.HOSPITAL,
                "병원 동행 부탁드려요", "○○병원", NOW.plusDays(1), NOW.plusDays(1).plusHours(3), null));
        assignmentRepository.save(RequestAssignment.assign(request, TARGET_ID, 1, NOW.plusHours(12)));
        Long requestId = request.getId();

        // 두 스레드가 기다리다가 동시에 출발함
        int threads = 2;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        Callable<String> accept = () -> {
            ready.countDown();
            start.await();
            try {
                service.respond(requestId, TARGET_ID, AssignmentResponseStatus.AVAILABLE);
                return "SUCCESS";
            } catch (BusinessException e) {
                return e.getErrorCode().getCode();
            }
        };

        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(accept));
        }
        ready.await();
        start.countDown();
        List<String> results = new ArrayList<>();
        for (Future<String> future : futures) {
            results.add(future.get());
        }
        executor.shutdown();

        // 하나는 성공, 나머지는 이미 수락됨
        assertThat(results).containsExactlyInAnyOrder(
                "SUCCESS", CareRequestErrorCode.REQUEST_ALREADY_ACCEPTED.getCode());

        // DB에도 수락이 한 번만 반영됨

        assertThat(careRequestRepository.findById(requestId).orElseThrow().getStatus())
                .isEqualTo(CareRequestStatus.ACCEPTED);
        assertThat(eventRepository.findAllByCareRequestIdOrderByOccurredAtAscIdAsc(requestId))
                .filteredOn(e -> e.getEventType() == ChangeEventType.RESPONSE_CHANGED)
                .hasSize(1);
    }
}
