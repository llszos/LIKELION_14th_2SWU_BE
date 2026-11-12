package com.gyeot.domain.situation.service;

import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.entity.SituationParticipant;
import com.gyeot.domain.situation.entity.SituationParticipantRole;
import com.gyeot.domain.situation.entity.SituationStatus;
import com.gyeot.domain.situation.event.SituationEndedEvent;
import com.gyeot.domain.situation.repository.SituationEndRepository;
import com.gyeot.global.config.ClockConfiguration;
import com.gyeot.global.config.JpaAuditingConfig;
import com.gyeot.global.exception.BusinessException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.gyeot.domain.situation.exception.SituationErrorCode.SITUATION_ALREADY_ENDED;
import static org.assertj.core.api.Assertions.*;

/** DB 저장·실제 JPQL 및 서로 다른 트랜잭션의 동시 종료를 검증합니다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, SafetyInformationCipher.class, ClockConfiguration.class,
        SituationEndService.class, SituationEndProcessor.class, SituationExpirationService.class,
        SituationEndIntegrationTest.EventConfig.class})
@ActiveProfiles("test")
@Testcontainers
class SituationEndIntegrationTest {
    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    @Autowired private EntityManager entityManager;
    @Autowired private SituationEndService service;
    @Autowired private SituationEndRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EventRecorder recorder;
    @Autowired private SituationExpirationService expirationService;

    @BeforeEach
    void clearEvents() {
        recorder.events.clear();
        recorder.failForId = null;
    }

    @Test
    void 종료_상태와_시각을_저장하고_이벤트와_일치시킨다() {
        Situation situation = saveSituation();
        var response = service.end(situation.getId(), 10L);
        entityManager.flush();
        entityManager.clear();

        Situation found = entityManager.find(Situation.class, situation.getId());
        assertThat(found.getStatus()).isEqualTo(SituationStatus.ENDED);
        assertThat(found.getEndedAt()).isEqualTo(response.endedAt());
        assertThat(recorder.events).containsExactly(new SituationEndedEvent(found.getId(), found.getEndedAt()));
        assertThatThrownBy(() -> service.end(found.getId(), 10L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SITUATION_ALREADY_ENDED));
        assertThat(recorder.events).hasSize(1);
    }

    @Test
    void 참여_조회는_상황과_사용자와_ACTIVE를_구분한다() {
        Situation situation = saveSituation();
        Situation other = saveSituation();
        var joinedAt = LocalDateTime.of(2026, 11, 1, 9, 0);
        entityManager.persist(SituationParticipant.create(situation, 10L, SituationParticipantRole.OWNER, joinedAt));
        var left = SituationParticipant.create(situation, 20L, SituationParticipantRole.PARTICIPANT, joinedAt);
        left.leave(joinedAt.plusHours(1));
        entityManager.persist(left);
        entityManager.persist(SituationParticipant.create(other, 30L, SituationParticipantRole.PARTICIPANT, joinedAt));
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.hasActiveParticipation(situation.getId(), 10L)).isTrue();
        assertThat(repository.hasActiveParticipation(situation.getId(), 20L)).isFalse();
        assertThat(repository.hasActiveParticipation(situation.getId(), 30L)).isFalse();
        assertThat(repository.hasActiveParticipation(Long.MAX_VALUE, 10L)).isFalse();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 동시_종료는_하나만_성공하고_이벤트도_하나이다() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        Long situationId = transaction.execute(status -> {
            Situation situation = saveSituation();
            entityManager.flush();
            return situation.getId();
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            var task = (java.util.concurrent.Callable<String>) () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("동시 실행 시작 대기 시간 초과");
                }
                try {
                    service.end(situationId, 10L);
                    return "SUCCESS";
                } catch (BusinessException e) {
                    return e.getErrorCode().getCode();
                }
            };
            var first = executor.submit(task);
            var second = executor.submit(task);
            boolean bothReady = ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            assertThat(bothReady).isTrue();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SUCCESS", "SITUATION_ALREADY_ENDED");
            assertThat(recorder.events).hasSize(1);
            transaction.executeWithoutResult(status -> {
                Situation found = entityManager.find(Situation.class, situationId);
                assertThat(found.getStatus()).isEqualTo(SituationStatus.ENDED);
                assertThat(recorder.events.peek().occurredAt()).isEqualTo(found.getEndedAt());
            });
        } finally {
            transaction.executeWithoutResult(status -> entityManager.remove(entityManager.find(Situation.class, situationId)));
        }
    }

    private Situation saveSituation() {
        Situation situation = Situation.create(10L, "돌봄", "병원 동행",
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));
        entityManager.persist(situation);
        return situation;
    }

    @Test
    void 만료_후보는_ACTIVE이면서_오늘보다_이전인_상황만_포함한다() {
        LocalDate today = LocalDate.of(2026, 11, 12);
        Situation yesterday = Situation.create(10L, "돌봄", "어제", today.minusDays(10), today.minusDays(1));
        Situation old = Situation.create(10L, "돌봄", "누적 만료", today.minusDays(10), today.minusDays(5));
        Situation current = Situation.create(10L, "돌봄", "오늘", today.minusDays(10), today);
        Situation future = Situation.create(10L, "돌봄", "미래", today, today.plusDays(1));
        Situation ended = Situation.create(10L, "돌봄", "종료됨", today.minusDays(10), today.minusDays(1));
        ended.end(today.atStartOfDay());
        for (Situation situation : List.of(yesterday, old, current, future, ended)) {
            entityManager.persist(situation);
        }
        entityManager.flush();
        entityManager.clear();
        assertThat(repository.findExpiredIds(today, 0L, org.springframework.data.domain.PageRequest.of(0, 100))).containsExactlyInAnyOrder(yesterday.getId(), old.getId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 한_대상_롤백_후에도_다른_대상은_종료하고_다음_실행에서_재시도한다() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        List<Long> ids = transaction.execute(status -> {
            LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
            Situation first = Situation.create(10L, "돌봄", "실패 대상", today.minusDays(10), today.minusDays(1));
            Situation second = Situation.create(10L, "돌봄", "성공 대상", today.minusDays(10), today.minusDays(2));
            entityManager.persist(first);
            entityManager.persist(second);
            entityManager.flush();
            return List.of(first.getId(), second.getId());
        });
        try {
            recorder.failForId = ids.get(0);
            var scheduler = new com.gyeot.domain.situation.scheduler.SituationExpirationScheduler(expirationService);
            scheduler.expireSituations();
            transaction.executeWithoutResult(status -> {
                assertThat(entityManager.find(Situation.class, ids.get(0)).getStatus()).isEqualTo(SituationStatus.ACTIVE);
                assertThat(entityManager.find(Situation.class, ids.get(1)).getStatus()).isEqualTo(SituationStatus.ENDED);
            });
            assertThat(recorder.events).hasSize(1);
            recorder.failForId = null;
            scheduler.expireSituations();
            scheduler.expireSituations();
            assertThat(recorder.events).hasSize(2);
            transaction.executeWithoutResult(status -> {
                for (Long id : ids) {
                    Situation found = entityManager.find(Situation.class, id);
                    assertThat(found.getStatus()).isEqualTo(SituationStatus.ENDED);
                    assertThat(recorder.events).contains(new SituationEndedEvent(id, found.getEndedAt()));
                }
            });
        } finally {
            recorder.failForId = null;
            transaction.executeWithoutResult(status -> {
                for (Long id : ids) {
                    entityManager.remove(entityManager.find(Situation.class, id));
                }
            });
        }
    }

    @Test
    void 만료_후보는_100개씩_ID_오름차순으로_누락없이_조회한다() {
        LocalDate today = LocalDate.of(2026, 11, 12);
        var expected = new java.util.ArrayList<Long>();
        for (int i = 0; i < 105; i++) {
            Situation situation = Situation.create(10L, "돌봄", "만료 후보",
                    today.minusDays(10), today.minusDays(1));
            entityManager.persist(situation);
            expected.add(situation.getId());
        }
        entityManager.flush();
        entityManager.clear();
        var page = org.springframework.data.domain.PageRequest.of(0, 100);
        var first = repository.findExpiredIds(today, 0L, page);
        assertThat(first).containsExactlyElementsOf(expected.subList(0, 100));
        var last = repository.findExpiredIds(today, first.getLast(), page);
        assertThat(last).containsExactlyElementsOf(expected.subList(100, 105));
        assertThat(repository.findExpiredIds(today, last.getLast(), page)).isEmpty();
    }

    @TestConfiguration
    static class EventConfig {
        @Bean
        EventRecorder eventRecorder() {
            return new EventRecorder();
        }
    }

    static class EventRecorder {
        final ConcurrentLinkedQueue<SituationEndedEvent> events = new ConcurrentLinkedQueue<>();
        volatile Long failForId;

        @EventListener
        public void onEnded(SituationEndedEvent event) {
            if (event.situationId().equals(failForId)) {
                throw new IllegalStateException("test listener failure");
            }
            events.add(event);
        }
    }
}
