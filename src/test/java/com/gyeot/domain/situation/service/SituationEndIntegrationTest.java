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
        SituationEndService.class, SituationEndProcessor.class, SituationEndIntegrationTest.EventConfig.class})
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

    @BeforeEach
    void clearEvents() {
        recorder.events.clear();
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

    @TestConfiguration
    static class EventConfig {
        @Bean
        EventRecorder eventRecorder() {
            return new EventRecorder();
        }
    }

    static class EventRecorder {
        final ConcurrentLinkedQueue<SituationEndedEvent> events = new ConcurrentLinkedQueue<>();

        @EventListener
        public void onEnded(SituationEndedEvent event) {
            events.add(event);
        }
    }
}
