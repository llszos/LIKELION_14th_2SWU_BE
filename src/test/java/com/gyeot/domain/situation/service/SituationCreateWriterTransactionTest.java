package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.dto.response.SituationCreateResponse;
import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.entity.SituationParticipant;
import com.gyeot.domain.situation.repository.SituationParticipantRepository;
import com.gyeot.domain.situation.repository.SituationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 실제 Spring 트랜잭션 프록시와 리소스 없는 기록용 매니저를 사용합니다.
 * 두 저장 호출의 경계·예외 시 rollback 요청을 확인하며 실제 DB 원자성은 검증하지 않습니다.
 */
class SituationCreateWriterTransactionTest {
    private AnnotationConfigApplicationContext context;
    private SituationRepository situations;
    private SituationParticipantRepository participants;
    private SituationCreateWriter writer;
    private RecordingTransactionManager transactions;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(Config.class);
        situations = context.getBean(SituationRepository.class);
        participants = context.getBean(SituationParticipantRepository.class);
        writer = context.getBean(SituationCreateWriter.class);
        transactions = context.getBean(RecordingTransactionManager.class);
        when(situations.save(any(Situation.class))).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            Situation situation = invocation.getArgument(0);
            ReflectionTestUtils.setField(situation, "id", 7L);
            return situation;
        });
        when(participants.save(any(SituationParticipant.class))).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.getArgument(0);
        });
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void bothSavesRunInsideOneWriteTransaction() {
        assertTrue(AopUtils.isAopProxy(writer));
        assertEquals(7L, save().situationId());
        assertEquals(1, transactions.begins);
        assertEquals(1, transactions.commits);
        assertEquals(0, transactions.rollbacks);
        verify(situations).save(any(Situation.class));
        verify(participants).save(any(SituationParticipant.class));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    void ownerFailureRequestsRollbackOfTheSharedTransaction() {
        var failure = new IllegalStateException("owner save failed");
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            throw failure;
        }).when(participants).save(any(SituationParticipant.class));
        assertSame(failure, assertThrows(IllegalStateException.class, this::save));
        assertRolledBack();
        verify(situations).save(any(Situation.class));
    }

    @Test
    void situationFailureRequestsRollbackAndDoesNotSaveOwner() {
        var failure = new IllegalStateException("situation save failed");
        doThrow(failure).when(situations).save(any(Situation.class));
        assertSame(failure, assertThrows(IllegalStateException.class, this::save));
        assertRolledBack();
        verifyNoInteractions(participants);
    }

    private SituationCreateResponse save() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 20, 9, 0);
        return writer.save(10L, "검증된 종류", "회복 지원", start, start.plusHours(1));
    }

    private void assertRolledBack() {
        assertEquals(1, transactions.begins);
        assertEquals(0, transactions.commits);
        assertEquals(1, transactions.rollbacks);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Config {
        @Bean SituationRepository situations() { return mock(SituationRepository.class); }
        @Bean SituationParticipantRepository participants() { return mock(SituationParticipantRepository.class); }
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC); }
        @Bean RecordingTransactionManager transactionManager() { return new RecordingTransactionManager(); }
        @Bean SituationCreateWriter writer(SituationRepository situations, SituationParticipantRepository participants, Clock clock) {
            return new SituationCreateWriter(situations, participants, clock);
        }
    }

    static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        int begins;
        int commits;
        int rollbacks;

        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { begins++; }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
