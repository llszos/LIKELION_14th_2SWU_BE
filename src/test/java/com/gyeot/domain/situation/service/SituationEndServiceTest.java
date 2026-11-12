package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.entity.SituationStatus;
import com.gyeot.domain.situation.event.SituationEndedEvent;
import com.gyeot.domain.situation.repository.SituationEndRepository;
import com.gyeot.global.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.gyeot.domain.situation.exception.SituationErrorCode.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SituationEndServiceTest {
    private static final LocalDateTime ENDED_AT = LocalDateTime.of(2026, 11, 12, 12, 0);
    private SituationEndRepository repository;
    private ApplicationEventPublisher publisher;
    private SituationEndProcessor processor;
    private SituationEndService service;
    private Situation situation;

    @BeforeEach
    void setUp() {
        repository = mock(SituationEndRepository.class);
        publisher = mock(ApplicationEventPublisher.class);
        // UTC Clock을 받아도 도메인 종료 시각은 한국 시간으로 기록합니다.
        processor = new SituationEndProcessor(Clock.fixed(Instant.parse("2026-11-12T03:00:00Z"), ZoneOffset.UTC), publisher);
        service = new SituationEndService(repository, processor);
        situation = Situation.create(10L, "돌봄", "병원 동행", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));
        ReflectionTestUtils.setField(situation, "id", 1L);
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(situation));
    }

    @Test
    void 생성자가_종료하면_한국_시각과_이벤트가_일치한다() {
        var response = service.end(1L, 10L);
        assertThat(response.situationId()).isEqualTo(1L);
        assertThat(response.status()).isEqualTo(SituationStatus.ENDED);
        assertThat(response.endedAt()).isEqualTo(ENDED_AT);
        ArgumentCaptor<SituationEndedEvent> event = ArgumentCaptor.forClass(SituationEndedEvent.class);
        verify(publisher).publishEvent(event.capture());
        assertThat(event.getValue()).isEqualTo(new SituationEndedEvent(1L, response.endedAt()));
        verify(repository, never()).hasActiveParticipation(any(), any());
    }

    @Test
    void ACTIVE_참여자인_비생성자는_403이다() {
        when(repository.hasActiveParticipation(1L, 20L)).thenReturn(true);
        assertThatThrownBy(() -> service.end(1L, 20L)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(SITUATION_FORBIDDEN));
        assertThat(situation.getStatus()).isEqualTo(SituationStatus.ACTIVE);
        verifyNoInteractions(publisher);
    }

    @Test
    void 무관하거나_이탈한_사용자는_404이다() {
        when(repository.hasActiveParticipation(1L, 20L)).thenReturn(false);
        assertThatThrownBy(() -> service.end(1L, 20L)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(SITUATION_NOT_FOUND));
        verifyNoInteractions(publisher);
    }

    @Test
    void 상황이_없으면_404이다() {
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.end(1L, 10L)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(SITUATION_NOT_FOUND));
        verifyNoInteractions(publisher);
    }

    @Test
    void 이미_종료되었으면_409이고_최초_시각을_보존한다() {
        situation.end(ENDED_AT.minusDays(1));
        assertThatThrownBy(() -> service.end(1L, 10L)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(SITUATION_ALREADY_ENDED));
        assertThat(situation.getEndedAt()).isEqualTo(ENDED_AT.minusDays(1));
        verifyNoInteractions(publisher);
    }

    @Test
    void 직접_종료를_반복해도_이벤트는_한번이다() {
        service.end(1L, 10L);
        assertThatThrownBy(() -> service.end(1L, 10L)).isInstanceOf(BusinessException.class);
        assertThat(situation.getEndedAt()).isEqualTo(ENDED_AT);
        verify(publisher, times(1)).publishEvent(any(SituationEndedEvent.class));
    }

    @Test
    void 공통_처리기는_반복_종료를_건너뛴다() {
        assertThat(processor.endIfActive(situation)).isTrue();
        assertThat(processor.endIfActive(situation)).isFalse();
        assertThat(situation.getEndedAt()).isEqualTo(ENDED_AT);
        verify(publisher, times(1)).publishEvent(any(SituationEndedEvent.class));
    }

    @Test
    void 서비스와_처리기_프록시는_같은_활성_트랜잭션에서_실행된다() {
        var manager = new LocalTransactionManager();
        var processorProxy = transactionalProxy(processor, manager);
        var serviceProxy = transactionalProxy(new SituationEndService(repository, processorProxy), manager);
        when(repository.findByIdForUpdate(1L)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return Optional.of(situation);
        });
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return null;
        }).when(publisher).publishEvent(any(SituationEndedEvent.class));

        serviceProxy.end(1L, 10L);

        assertThat(manager.begins).isEqualTo(1);
        assertThat(manager.commits).isEqualTo(1);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        verify(publisher).publishEvent(any(SituationEndedEvent.class));
    }

    @Test
    void 처리기_프록시는_트랜잭션_없는_호출을_거부한다() {
        var processorProxy = transactionalProxy(processor, new LocalTransactionManager());
        assertThatThrownBy(() -> processorProxy.endIfActive(situation))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(situation.getStatus()).isEqualTo(SituationStatus.ACTIVE);
        verifyNoInteractions(publisher);
    }

    @SuppressWarnings("unchecked")
    private <T> T transactionalProxy(T target, LocalTransactionManager manager) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        factory.addAdvice(interceptor);
        return (T) factory.getProxy();
    }

    /** Spring의 실제 전파·프록시 동작만 검증합니다. DB 트랜잭션과 잠금은 통합 테스트 대상입니다. */
    private static class LocalTransactionManager extends AbstractPlatformTransactionManager {
        int begins;
        int commits;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return TransactionSynchronizationManager.isActualTransactionActive();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            begins++;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
