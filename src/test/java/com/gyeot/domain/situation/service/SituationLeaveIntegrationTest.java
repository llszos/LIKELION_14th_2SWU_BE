package com.gyeot.domain.situation.service;

import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
import com.gyeot.domain.situation.entity.*;
import com.gyeot.domain.situation.event.SituationParticipantRemovedEvent;
import com.gyeot.domain.situation.repository.*;
import com.gyeot.global.config.ClockConfiguration;
import com.gyeot.global.config.JpaAuditingConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/** 실제 MySQL과 독립 트랜잭션으로 저장, 잠금, 롤백을 검증합니다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, SafetyInformationCipher.class, ClockConfiguration.class,
        SituationLeaveProcessor.class, JpaSituationLeaveStore.class, SituationLeaveIntegrationTest.Events.class})
@ActiveProfiles("test")
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SituationLeaveIntegrationTest {
    @Container @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    @Autowired SituationLeaveProcessor processor;
    @Autowired SituationRepository situations;
    @Autowired SituationParticipantRepository participants;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Events events;
    @Autowired jakarta.persistence.EntityManager diagnosticEntityManager;
    private Long situationId;
    private Long participantId;

    @BeforeEach
    void seed() {
        events.count.set(0);
        events.fail = false;
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            var situation = situations.save(Situation.create(1L, "돌봄", "동행",
                    LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)));
            situationId = situation.getId();
            participantId = participants.save(SituationParticipant.create(situation, 2L,
                    SituationParticipantRole.PARTICIPANT, LocalDateTime.of(2026, 11, 1, 9, 0))).getId();
        });
    }

    @Test
    void persistsFirstTimeAndRetryDoesNotPublishAgain() {
        var first = processor.leave(situationId, 2L);
        assertThat(processor.leave(situationId, 2L)).isEqualTo(first);
        assertThat(participants.findById(participantId).orElseThrow().getLeftAt()).isEqualTo(first.leftAt());
        assertThat(events.count.get()).isEqualTo(1);
    }

    @Test
    void concurrentLeavesSerializeAndPublishOnce() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<com.gyeot.domain.situation.dto.response.SituationLeaveResponse> leave = () -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                return processor.leave(situationId, 2L);
            };
            var first = executor.submit(leave);
            var second = executor.submit(leave);
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS));
        }
        assertThat(participants.findById(participantId).orElseThrow().getStatus()).isEqualTo(SituationParticipantStatus.LEFT);
        assertThat(events.count.get()).isEqualTo(1);
    }

    @Test
    void synchronousEventFailureRollsBackParticipation() {
        events.fail = true;
        assertThatThrownBy(() -> processor.leave(situationId, 2L)).isInstanceOf(IllegalStateException.class);
        var participant = participants.findById(participantId).orElseThrow();
        assertThat(participant.getStatus()).isEqualTo(SituationParticipantStatus.ACTIVE);
        assertThat(participant.getLeftAt()).isNull();
    }

    @Test
    void refreshesPreviouslyLoadedParticipation() {
        // Only this synthetic-data test enables SQL/bind diagnostics, and restores levels on failure.
        var sqlLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.hibernate.SQL");
        var bindLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.hibernate.orm.jdbc.bind");
        var previousSqlLevel = sqlLogger.getLevel();
        var previousBindLevel = bindLogger.getLevel();
        sqlLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        bindLogger.setLevel(ch.qos.logback.classic.Level.TRACE);
        try {
        var originalLeftAt = LocalDateTime.of(2026, 11, 15, 9, 0);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            participants.findById(participantId).orElseThrow();
            logContext("outer-before-inner");
            var independent = new TransactionTemplate(transactionManager);
            independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            independent.executeWithoutResult(other -> {
                logContext("inner");
                var changed = participants.findById(participantId).orElseThrow();
                changed.leave(originalLeftAt);
                System.out.printf("CI103 inner-before-commit participant=%s status=%s leftAt=%s%n",
                        participantId, changed.getStatus(), changed.getLeftAt());
            });
            logContext("outer-resumed");
            var response = processor.leave(situationId, 2L);
            System.out.printf("CI103 expected=%s actual=%s status=%s events=%s%n",
                    originalLeftAt, response.leftAt(), response.status(), events.count.get());
            assertThat(response.leftAt()).isEqualTo(originalLeftAt);
        });
        assertThat(events.count.get()).isZero();
        } finally {
            sqlLogger.setLevel(previousSqlLevel);
            bindLogger.setLevel(previousBindLevel);
        }
    }

    // Diagnostics only: metadata reads, no entity read/flush/refresh/isolation change.
    private void logContext(String phase) {
        var session = diagnosticEntityManager.unwrap(org.hibernate.Session.class);
        session.doWork(connection -> {
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT CONNECTION_ID(), @@transaction_isolation")) {
                result.next();
                System.out.printf("CI103 phase=%s session=%s connection=%s isolation=%s jdbcIsolation=%s%n",
                        phase, System.identityHashCode(session), result.getLong(1), result.getString(2),
                        connection.getTransactionIsolation());
            }
        });
    }

    static class Events {
        final AtomicInteger count = new AtomicInteger();
        volatile boolean fail;
        @EventListener
        public void onLeave(SituationParticipantRemovedEvent event) {
            count.incrementAndGet();
            if (fail) throw new IllegalStateException("event failure");
        }
    }
}
