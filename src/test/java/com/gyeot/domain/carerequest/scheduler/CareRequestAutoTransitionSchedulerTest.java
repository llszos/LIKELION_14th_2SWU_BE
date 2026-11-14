package com.gyeot.domain.carerequest.scheduler;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.entity.RequestChangeEvent;
import com.gyeot.domain.carerequest.entity.RequestChangeProposal;
import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import com.gyeot.domain.carerequest.enums.CancelReason;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.ChangeEventType;
import com.gyeot.domain.carerequest.enums.ChangeableField;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.ProposalStatus;
import com.gyeot.domain.carerequest.event.CareRequestCompletedEvent;
import com.gyeot.domain.carerequest.event.CareRequestEvent;
import com.gyeot.domain.carerequest.event.CareRequestExpiredEvent;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeEventRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeProposalRepository;

import com.gyeot.domain.carerequest.service.CareRequestAutoTransitionService;
import com.gyeot.domain.carerequest.service.CareRequestLifecycle;
import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 요청 자동 처리 스케줄러(#75)를 실제 MySQL로 확인합니다.
 *
 * - 처리는 건마다 새 트랜잭션(REQUIRES_NEW)이라, 테스트 데이터도 진짜로 커밋해야 보입니다.
 *   그래서 테스트 메서드는 트랜잭션 없이 돌고(NOT_SUPPORTED), 준비 데이터는 TransactionTemplate으로 커밋한 뒤
 *   테스트마다 테이블을 비웁니다. (CareRequestStatusConcurrencyTest와 같은 방식)
 * - 시간을 기다리지 않고 runAt(시각)을 직접 호출합니다. (SchedulingConfig 규칙)
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAuditingConfig.class,
        SafetyInformationCipher.class, // 안심카드 엔티티의 암호화 Converter가 필요로 함
        CareRequestLifecycle.class,
        CareRequestAutoTransitionService.class,
        CareRequestAutoTransitionScheduler.class
})
@ActiveProfiles("test")
@Testcontainers
@RecordApplicationEvents
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class CareRequestAutoTransitionSchedulerTest {
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

    @Autowired
    private CareRequestAutoTransitionScheduler scheduler;
    @Autowired
    private CareRequestRepository careRequestRepository;
    @Autowired
    private RequestAssignmentRepository assignmentRepository;
    @Autowired
    private RequestChangeProposalRepository proposalRepository;
    @Autowired
    private RequestChangeEventRepository eventRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ApplicationEvents events;

    @AfterEach
    void cleanUp() {
        eventRepository.deleteAllInBatch();
        proposalRepository.deleteAllInBatch();
        assignmentRepository.deleteAllInBatch();
        careRequestRepository.deleteAllInBatch();
    }

    // === 시작 시각 도달 ===

    @Test
    void 시작_시각이_된_수락됨_요청은_진행_중이_되고_수행자는_없다() {
        Long requestId = acceptedRequest(NOW, NOW.plusHours(3));

        scheduler.runAt(NOW);

        CareRequest request = reload(requestId);
        assertThat(request.getStatus()).isEqualTo(CareRequestStatus.IN_PROGRESS);
        assertThat(request.getStartedAt()).isEqualTo(NOW);
        assertThat(history(requestId)).last()
                .satisfies(event -> {
                    assertThat(event.getEventType()).isEqualTo(ChangeEventType.REQUEST_STATUS_CHANGED);
                    assertThat(event.getActorUserId()).isNull(); // 시스템 처리
                });
        assertThat(publishedEvents()).isEmpty(); // 시작은 다른 영역에 알릴 일이 없음
    }

    @Test
    void 시작하면_대기_중_수정안이_REQUEST_STARTED로_무효가_된다() {
        Long requestId = acceptedRequest(NOW, NOW.plusHours(3));
        Long proposalId = proposal(requestId, NOW.minusHours(2)); // 기한 = 시작 시각(NOW), 아직 지나지 않음

        scheduler.runAt(NOW);

        assertThat(proposalRepository.findById(proposalId).orElseThrow().getStatus())
                .isEqualTo(ProposalStatus.INVALIDATED);
        assertThat(invalidationReason(requestId)).isEqualTo("REQUEST_STARTED");
    }

    @Test
    void 시작_시각이_된_요청됨_요청은_만료되고_열린_지정은_REQUEST_ENDED로_마감된다() {
        Long requestId = requestedRequest(NOW, NOW.plusHours(3));

        scheduler.runAt(NOW);

        assertThat(reload(requestId).getStatus()).isEqualTo(CareRequestStatus.EXPIRED);
        assertThat(assignmentRepository.findAllByCareRequestIdOrderByAssignmentOrderAsc(requestId))
                .singleElement()
                .extracting(RequestAssignment::getCloseReason).isEqualTo(AssignmentCloseReason.REQUEST_ENDED);
        assertThat(publishedEvents()).singleElement()
                .isInstanceOfSatisfying(CareRequestExpiredEvent.class, event -> {
                    assertThat(event.requestId()).isEqualTo(requestId);
                    assertThat(event.targetUserId()).isEqualTo(TARGET_ID); // 만료 당시 대상자 (D 권한 회수, B 안내)
                });
    }

    @Test
    void 시작_시각_전_요청은_그대로_둔다() {
        Long accepted = acceptedRequest(NOW.plusMinutes(1), NOW.plusHours(3));
        Long requested = requestedRequest(NOW.plusMinutes(1), NOW.plusHours(3));

        scheduler.runAt(NOW);

        assertThat(reload(accepted).getStatus()).isEqualTo(CareRequestStatus.ACCEPTED);
        assertThat(reload(requested).getStatus()).isEqualTo(CareRequestStatus.REQUESTED);
        assertThat(publishedEvents()).isEmpty();
    }

    // === 종료 시각 도달 ===

    @Test
    void 종료_시각이_된_진행_중_요청은_완료되고_완료한_사용자는_없다() {
        Long requestId = inProgressRequest(NOW.minusHours(3), NOW);

        scheduler.runAt(NOW);

        assertThat(reload(requestId).getStatus()).isEqualTo(CareRequestStatus.COMPLETED);
        assertThat(publishedEvents()).singleElement()
                .isInstanceOfSatisfying(CareRequestCompletedEvent.class, event -> {
                    assertThat(event.requestId()).isEqualTo(requestId);
                    assertThat(event.completedByUserId()).isNull(); // 종료 시각 도달 (D 권한 회수)
                });
    }

    @Test
    void 시작과_종료가_모두_지난_수락됨_요청은_한_번에_진행_중을_거쳐_완료된다() {
        Long requestId = acceptedRequest(NOW.minusHours(3), NOW.minusHours(1)); // 서버가 멈춰 있던 경우

        scheduler.runAt(NOW);

        assertThat(reload(requestId).getStatus()).isEqualTo(CareRequestStatus.COMPLETED);
        // 상태 변경 이력 2건: 수락됨 → 진행 중, 진행 중 → 완료 (준비 단계의 수락은 이력을 남기지 않음)
        assertThat(history(requestId))
                .extracting(event -> event.getEventDetails().get("new_status"))
                .containsExactly("IN_PROGRESS", "COMPLETED");
    }

    // === 재확인 기한 ===

    @Test
    void 재확인_기한이_지난_수정안은_DEADLINE_PASSED로_무효가_되고_요청은_그대로다() {
        Long requestId = acceptedRequest(NOW.plusDays(2), NOW.plusDays(2).plusHours(3));
        Long proposalId = proposal(requestId, NOW.minusHours(25)); // 기한 = 만든 시각 + 24시간 = NOW - 1시간

        scheduler.runAt(NOW);

        assertThat(proposalRepository.findById(proposalId).orElseThrow().getStatus())
                .isEqualTo(ProposalStatus.INVALIDATED);
        assertThat(invalidationReason(requestId)).isEqualTo("DEADLINE_PASSED");
        assertThat(reload(requestId).getStatus()).isEqualTo(CareRequestStatus.ACCEPTED); // 기존 내용, 수락 유지
        assertThat(publishedEvents()).isEmpty();
    }

    @Test
    void 재확인_기한이_남은_수정안은_그대로_둔다() {
        Long requestId = acceptedRequest(NOW.plusDays(2), NOW.plusDays(2).plusHours(3));
        Long proposalId = proposal(requestId, NOW.minusHours(1)); // 기한 = NOW + 23시간

        scheduler.runAt(NOW);

        assertThat(proposalRepository.findById(proposalId).orElseThrow().getStatus())
                .isEqualTo(ProposalStatus.PENDING);
    }

    // === 다시 실행, 이미 끝난 요청 ===

    @Test
    void 두_번_실행해도_결과와_이력이_같다() {
        Long requestId = requestedRequest(NOW, NOW.plusHours(3));

        scheduler.runAt(NOW);
        int historyAfterFirst = history(requestId).size();
        scheduler.runAt(NOW.plusMinutes(1));

        assertThat(reload(requestId).getStatus()).isEqualTo(CareRequestStatus.EXPIRED);
        assertThat(history(requestId)).hasSize(historyAfterFirst);
        assertThat(publishedEvents()).hasSize(1); // 만료 이벤트는 한 번만
    }

    @Test
    void 사용자가_먼저_취소한_요청은_시작_시각이_지나도_건드리지_않는다() {
        Long requestId = requestedRequest(NOW.minusMinutes(5), NOW.plusHours(3));
        inTx(() -> {
            careRequestRepository.findById(requestId).orElseThrow().cancel(CancelReason.REQUESTER_CANCELED, NOW.minusMinutes(10));
            return null;
        });

        scheduler.runAt(NOW);

        assertThat(reload(requestId).getStatus()).isEqualTo(CareRequestStatus.CANCELED);
        assertThat(publishedEvents()).isEmpty();
    }

    // === 준비 코드 ===

    /** 요청됨 요청 (대상자 미응답, 응답 기한 = 시작 시각) */
    private Long requestedRequest(LocalDateTime startsAt, LocalDateTime endsAt) {
        return inTx(() -> {
            CareRequest request = careRequestRepository.save(CareRequest.createGeneral(1L, REQUESTER_ID,
                    HelpType.HOSPITAL, "병원 동행 부탁드려요", "○○병원", startsAt, endsAt, null));
            assignmentRepository.save(RequestAssignment.assign(request, TARGET_ID, 1, startsAt));
            return request.getId();
        });
    }

    /** 수락됨 요청 (시작 하루 전에 수락) */
    private Long acceptedRequest(LocalDateTime startsAt, LocalDateTime endsAt) {
        Long requestId = requestedRequest(startsAt, endsAt);
        inTx(() -> {
            CareRequest request = careRequestRepository.findById(requestId).orElseThrow();
            assignmentOf(requestId).respond(AssignmentResponseStatus.AVAILABLE, startsAt.minusDays(1));
            request.accept();
            return null;
        });
        return requestId;
    }

    /** 진행 중 요청 (시작 시각에 시작) */
    private Long inProgressRequest(LocalDateTime startsAt, LocalDateTime endsAt) {
        Long requestId = acceptedRequest(startsAt, endsAt);
        inTx(() -> {
            careRequestRepository.findById(requestId).orElseThrow().start(startsAt);
            return null;
        });
        return requestId;
    }

    /** 장소만 바꾸는 대기 중 수정안. 기한 = min(만든 시각 + 24시간, 시작 시각) */
    private Long proposal(Long requestId, LocalDateTime createdAt) {
        return inTx(() -> {
            CareRequest request = careRequestRepository.findById(requestId).orElseThrow();
            return proposalRepository.save(RequestChangeProposal.create(request, assignmentOf(requestId),
                    Set.of(ChangeableField.PLACE), null, null, "△△병원", null, createdAt)).getId();
        });
    }

    private RequestAssignment assignmentOf(Long requestId) {
        return assignmentRepository.findByCareRequestIdAndTargetUserId(requestId, TARGET_ID).orElseThrow();
    }

    private CareRequest reload(Long requestId) {
        return careRequestRepository.findById(requestId).orElseThrow();
    }

    private List<RequestChangeEvent> history(Long requestId) {
        return eventRepository.findAllByCareRequestIdOrderByOccurredAtAscIdAsc(requestId);
    }

    /** 수정안 무효 이력에 남은 사유 (수정안 엔티티에는 사유 컬럼이 없고 이력에 남음) */
    private Object invalidationReason(Long requestId) {
        return history(requestId).stream()
                .filter(event -> event.getEventType() == ChangeEventType.RECONFIRMATION_INVALIDATED)
                .findFirst().orElseThrow()
                .getEventDetails().get("reason");
    }

    private List<CareRequestEvent> publishedEvents() {
        return events.stream(CareRequestEvent.class).toList();
    }

    private <T> T inTx(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }
}

