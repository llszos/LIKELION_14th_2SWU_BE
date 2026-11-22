package com.gyeot.domain.situation.service;

import com.gyeot.domain.relation.entity.RelationshipType;
import com.gyeot.domain.relation.event.RelationshipEndedEvent;
import com.gyeot.domain.situation.listener.SituationMembershipCleanupListener;
import com.gyeot.domain.situation.repository.SituationCleanupRepository;
import com.gyeot.domain.user.event.UserWithdrawnEvent;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 실제 Spring 프록시의 MANDATORY·예외 전파 검증. DB 롤백 증거를 대신하지 않습니다. */
class SituationMembershipCleanupTransactionTest {
    private final LocalDateTime now = LocalDateTime.of(2026, 11, 16, 12, 0);

    @Test void originalTransactionIsRequiredForBothEvents() {
        try (var context = context()) {
            assertThatThrownBy(() -> context.publishEvent(relation()))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> context.publishEvent(new UserWithdrawnEvent(20L, now)))
                    .isInstanceOf(IllegalTransactionStateException.class);
            verifyNoInteractions(context.getBean(SituationCleanupRepository.class));
        }
    }

    @Test void cleanupFailureRollsBackCallerInsteadOfStartingAnotherTransaction() {
        try (var context = context()) {
            var repository = context.getBean(SituationCleanupRepository.class);
            var manager = context.getBean(RecordingTransactionManager.class);
            when(repository.lockOwnedSituations(java.util.List.of(10L)))
                    .thenThrow(new IllegalStateException("store failure"));
            assertThatThrownBy(() -> new TransactionTemplate(manager)
                    .executeWithoutResult(status -> context.publishEvent(relation())))
                    .isInstanceOf(IllegalStateException.class).hasMessage("store failure");
            assertThat(manager.begins.get()).isEqualTo(1);
            assertThat(manager.rollbacks.get()).isEqualTo(1);
            assertThat(manager.commits.get()).isZero();
        }
    }

    private RelationshipEndedEvent relation() {
        return new RelationshipEndedEvent(7L, 10L, 20L, RelationshipType.MY_PERSON, now);
    }

    private AnnotationConfigApplicationContext context() {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(SituationCleanupRepository.class, () -> mock(SituationCleanupRepository.class));
        context.registerBean(EntityManager.class, () -> mock(EntityManager.class));
        context.register(Config.class, SituationMembershipCleanupService.class, SituationMembershipCleanupListener.class);
        context.refresh();
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Config {
        @Bean RecordingTransactionManager transactionManager() { return new RecordingTransactionManager(); }
    }

    static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private final ThreadLocal<Boolean> active = ThreadLocal.withInitial(() -> false);
        final AtomicInteger begins = new AtomicInteger();
        final AtomicInteger commits = new AtomicInteger();
        final AtomicInteger rollbacks = new AtomicInteger();
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected boolean isExistingTransaction(Object tx) { return active.get(); }
        @Override protected void doBegin(Object tx, org.springframework.transaction.TransactionDefinition definition) {
            active.set(true); begins.incrementAndGet();
        }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits.incrementAndGet(); }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks.incrementAndGet(); }
        @Override protected void doSetRollbackOnly(DefaultTransactionStatus status) { }
        @Override protected void doCleanupAfterCompletion(Object tx) { active.remove(); }
    }
}
