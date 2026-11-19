package com.gyeot.domain.situation.service;

import com.gyeot.domain.relation.entity.RelationshipStatus;
import com.gyeot.domain.relation.repository.RelationshipQueryRepository;
import com.gyeot.domain.user.entity.AccountStatus;
import com.gyeot.domain.user.repository.UserRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.TransactionDefinition;
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
import static org.mockito.Mockito.when;

/** 실제 MySQL과 독립 트랜잭션으로 저장, 잠금, 롤백을 검증합니다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, SafetyInformationCipher.class, ClockConfiguration.class,
        SituationLeaveProcessor.class, JpaSituationLeaveStore.class, SituationLeaveIntegrationTest.Events.class,
        SituationParticipantAddService.class, SituationParticipantRemovalService.class,
        SituationParticipantLockReader.class, SituationParticipantRemovalProcessor.class})
@ActiveProfiles("test")
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SituationLeaveIntegrationTest {
    @Container @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    @Autowired SituationLeaveProcessor processor;
    @Autowired SituationParticipantAddService addService;
    @Autowired SituationParticipantRemovalService removalService;
    @MockitoBean RelationshipQueryRepository relationships;
    @MockitoBean UserRepository users;
    @Autowired SituationRepository situations;
    @Autowired SituationParticipantRepository participants;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Events events;
    @Autowired jakarta.persistence.EntityManager entityManager;
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
            participants.save(SituationParticipant.create(situation, 1L, SituationParticipantRole.OWNER,
                    LocalDateTime.of(2026, 11, 1, 9, 0)));
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
        var originalLeftAt = LocalDateTime.of(2026, 11, 15, 9, 0);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            participants.findById(participantId).orElseThrow();
            var independent = new TransactionTemplate(transactionManager);
            independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            independent.executeWithoutResult(other ->
                    participants.findById(participantId).orElseThrow().leave(originalLeftAt));
            assertThat(processor.leave(situationId, 2L).leftAt()).isEqualTo(originalLeftAt);
        });
        assertThat(events.count.get()).isZero();
    }

    @Test
    void reloadsOnlyTargetAndPreservesUnrelatedManagedChanges() {
        Long otherId = new TransactionTemplate(transactionManager).execute(tx -> {
            var previousReference = participants.findById(participantId).orElseThrow();
            var unrelated = situations.save(Situation.create(9L, "돌봄", "다른 상황",
                    LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)));
            var endedAt = LocalDateTime.of(2026, 11, 15, 10, 0);
            unrelated.end(endedAt);
            var response = processor.leave(situationId, 2L);
            assertThat(entityManager.contains(previousReference)).isFalse();
            var current = participants.findById(participantId).orElseThrow();
            assertThat(current).isNotSameAs(previousReference);
            assertThat(entityManager.contains(current)).isTrue();
            assertThat(current.getStatus()).isEqualTo(SituationParticipantStatus.LEFT);
            assertThat(current.getLeftAt()).isEqualTo(response.leftAt());
            assertThat(entityManager.contains(unrelated)).isTrue();
            return unrelated.getId();
        });
        var savedOther = situations.findById(otherId).orElseThrow();
        assertThat(savedOther.getStatus()).isEqualTo(SituationStatus.ENDED);
        assertThat(savedOther.getEndedAt()).isEqualTo(LocalDateTime.of(2026, 11, 15, 10, 0));
        assertThat(events.count.get()).isEqualTo(1);
    }

    @Test
    void addReloadsPreloadedActiveHistoryAfterParticipantLeaves() {
        when(users.existsById(2L)).thenReturn(true);
        when(users.existsByIdAndAccountStatus(2L, AccountStatus.ACTIVE)).thenReturn(true);
        when(relationships.existsBySourceUserIdAndTargetUserIdAndStatus(1L, 2L, RelationshipStatus.ACTIVE))
                .thenReturn(true);
        var leftAt = LocalDateTime.of(2026, 11, 15, 9, 0);

        var addedId = new TransactionTemplate(transactionManager).execute(tx -> {
            assertThat(participants.findById(participantId).orElseThrow().getStatus()).isEqualTo(SituationParticipantStatus.ACTIVE);
            changeParticipationInNewTransaction(() -> participants.findById(participantId).orElseThrow().leave(leftAt));
            var added = addService.add(situationId, 1L, 2L);
            assertThat(added.participantId()).isNotEqualTo(participantId);
            return added.participantId();
        });

        assertThat(participants.findById(participantId).orElseThrow().getStatus()).isEqualTo(SituationParticipantStatus.LEFT);
        assertThat(participants.findById(addedId).orElseThrow().getStatus()).isEqualTo(SituationParticipantStatus.ACTIVE);
    }

    @Test
    void removalReloadsPreloadedTargetAndKeepsOriginalRemovalTime() {
        var removedAt = LocalDateTime.of(2026, 11, 15, 9, 0);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            assertThat(participants.findById(participantId).orElseThrow().getStatus()).isEqualTo(SituationParticipantStatus.ACTIVE);
            changeParticipationInNewTransaction(() -> participants.findById(participantId).orElseThrow().remove(removedAt));
            var result = removalService.remove(situationId, participantId, 1L);
            assertThat(result.status()).isEqualTo(SituationParticipantStatus.REMOVED);
            assertThat(result.leftAt()).isEqualTo(removedAt);
        });
        assertThat(participants.findById(participantId).orElseThrow().getLeftAt()).isEqualTo(removedAt);
        assertThat(events.count.get()).isZero();
    }

    private void changeParticipationInNewTransaction(Runnable change) {
        var independent = new TransactionTemplate(transactionManager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.executeWithoutResult(tx -> change.run());
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
