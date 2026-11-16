package com.gyeot.domain.situation.service;

import com.gyeot.domain.relation.entity.RelationshipType;
import com.gyeot.domain.relation.event.RelationshipEndedEvent;
import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
import com.gyeot.domain.situation.entity.*;
import com.gyeot.domain.situation.event.SituationParticipantRemovedEvent;
import com.gyeot.domain.situation.listener.SituationMembershipCleanupListener;
import com.gyeot.domain.situation.repository.*;
import com.gyeot.domain.user.event.UserWithdrawnEvent;
import com.gyeot.global.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

/** 실제 DB 잠금·RR·롤백 검증. A/C 제품 Service 자체의 통합 검증은 별도로 수행합니다. */
@DataJpaTest(properties = "spring.flyway.enabled=false")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, SafetyInformationCipher.class, SituationMembershipCleanupService.class,
        SituationMembershipCleanupListener.class, SituationMembershipCleanupIntegrationTest.Probe.class})
@ActiveProfiles("test")
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SituationMembershipCleanupIntegrationTest {
    @Container @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"));

    @Autowired SituationRepository situations;
    @Autowired SituationParticipantRepository participants;
    @Autowired EntityManager em;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Probe probe;
    private final LocalDateTime now = LocalDateTime.of(2026, 11, 16, 12, 0);
    private TransactionTemplate tx;

    @BeforeEach void setup() {
        tx = new TransactionTemplate(transactionManager);
        probe.events.clear();
        probe.fail.set(false);
    }

    @AfterEach void cleanup() {
        probe.fail.set(false);
        tx.executeWithoutResult(status -> { participants.deleteAllInBatch(); situations.deleteAllInBatch(); });
    }

    @Test void myPersonScopeExcludesReverseThirdPartyAndEnded() {
        var source = fixture(10L, 20L, false);
        var reverse = fixture(20L, 10L, false);
        var third = fixture(30L, 20L, false);
        var ended = fixture(10L, 20L, true);
        tx.executeWithoutResult(status -> publisher.publishEvent(event(RelationshipType.MY_PERSON)));
        assertThat(state(source)).isEqualTo(SituationParticipantStatus.REMOVED);
        assertThat(state(reverse)).isEqualTo(SituationParticipantStatus.ACTIVE);
        assertThat(state(third)).isEqualTo(SituationParticipantStatus.ACTIVE);
        assertThat(state(ended)).isEqualTo(SituationParticipantStatus.ACTIVE);
        assertThat(probe.events).hasSize(1);
    }

    @Test void guardianCleansBothDirectionsOnce() {
        var first = fixture(10L, 20L, false);
        var second = fixture(20L, 10L, false);
        tx.executeWithoutResult(status -> publisher.publishEvent(event(RelationshipType.GUARDIAN)));
        tx.executeWithoutResult(status -> publisher.publishEvent(event(RelationshipType.GUARDIAN)));
        assertThat(state(first)).isEqualTo(SituationParticipantStatus.REMOVED);
        assertThat(state(second)).isEqualTo(SituationParticipantStatus.REMOVED);
        assertThat(probe.events).hasSize(2);
    }

    @Test void synchronousFailureRollsBackAllBChangesAndCanRetry() {
        var first = fixture(10L, 20L, false);
        var second = fixture(20L, 10L, false);
        probe.fail.set(true);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> publisher.publishEvent(event(RelationshipType.GUARDIAN))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(state(first)).isEqualTo(SituationParticipantStatus.ACTIVE);
        assertThat(state(second)).isEqualTo(SituationParticipantStatus.ACTIVE);
        probe.fail.set(false);
        probe.events.clear();
        tx.executeWithoutResult(status -> publisher.publishEvent(event(RelationshipType.GUARDIAN)));
        assertThat(probe.events).hasSize(2);
    }

    @Test void withdrawalLeavesOtherSituationButPreservesOwnedSituationAndPublishesNoBEvent() {
        var general = fixture(30L, 20L, false);
        var owned = fixture(20L, 10L, false);
        tx.executeWithoutResult(status -> publisher.publishEvent(new UserWithdrawnEvent(20L, now)));
        tx.executeWithoutResult(status -> publisher.publishEvent(new UserWithdrawnEvent(20L, now)));
        assertThat(state(general)).isEqualTo(SituationParticipantStatus.LEFT);
        assertThat(state(owned)).isEqualTo(SituationParticipantStatus.ACTIVE);
        Long situationCount = tx.execute(status -> situations.count());
        assertThat(situationCount).isEqualTo(2L);
        assertThat(probe.events).isEmpty();
    }

    @Test void currentLockReadDoesNotRepeatEventFromPreviouslyManagedActiveState() {
        var id = fixture(10L, 20L, false);
        tx.executeWithoutResult(status -> {
            var stale = participants.findById(id).orElseThrow();
            var independent = new TransactionTemplate(transactionManager);
            independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            independent.executeWithoutResult(inner -> participants.findById(id).orElseThrow().remove(now.minusMinutes(1)));
            assertThat(stale.getStatus()).isEqualTo(SituationParticipantStatus.ACTIVE);
            publisher.publishEvent(event(RelationshipType.MY_PERSON));
        });
        assertThat(state(id)).isEqualTo(SituationParticipantStatus.REMOVED);
        assertThat(probe.events).isEmpty();
    }

    @Test void concurrentDuplicateWaitsForParentLockAndPublishesOnce() throws Exception {
        var id = fixture(10L, 20L, false);
        var processed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> tx.executeWithoutResult(status -> {
                publisher.publishEvent(event(RelationshipType.MY_PERSON));
                processed.countDown();
                await(release);
            }));
            try {
                assertThat(processed.await(10, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> tx.executeWithoutResult(status -> {
                    secondStarted.countDown();
                    publisher.publishEvent(event(RelationshipType.MY_PERSON));
                }));
                assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                first.get(10, TimeUnit.SECONDS);
                second.get(10, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
        assertThat(state(id)).isEqualTo(SituationParticipantStatus.REMOVED);
        assertThat(probe.events).hasSize(1);
    }

    private void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("latch timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }

    private Long fixture(Long ownerId, Long targetId, boolean ended) {
        return tx.execute(status -> {
            var s = situations.save(Situation.create(ownerId, "RECOVERY", "회복", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)));
            participants.save(SituationParticipant.create(s, ownerId, SituationParticipantRole.OWNER, now.minusDays(1)));
            var p = participants.save(SituationParticipant.create(s, targetId, SituationParticipantRole.PARTICIPANT, now.minusDays(1)));
            if (ended) s.end(now.minusHours(1));
            return p.getId();
        });
    }

    private SituationParticipantStatus state(Long id) {
        return tx.execute(status -> participants.findById(id).orElseThrow().getStatus());
    }

    private RelationshipEndedEvent event(RelationshipType type) {
        return new RelationshipEndedEvent(7L, 10L, 20L, type, now);
    }

    static class Probe {
        final CopyOnWriteArrayList<SituationParticipantRemovedEvent> events = new CopyOnWriteArrayList<>();
        final AtomicBoolean fail = new AtomicBoolean();
        @EventListener public void onRemoved(SituationParticipantRemovedEvent event) {
            if (fail.get()) throw new IllegalStateException("downstream failure");
            events.add(event);
        }
    }
}
