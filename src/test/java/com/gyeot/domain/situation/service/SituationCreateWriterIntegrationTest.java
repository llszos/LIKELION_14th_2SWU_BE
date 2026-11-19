package com.gyeot.domain.situation.service;

import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
import com.gyeot.domain.situation.entity.SituationParticipant;
import com.gyeot.domain.situation.entity.SituationParticipantRole;
import com.gyeot.domain.situation.entity.SituationParticipantStatus;
import com.gyeot.domain.situation.entity.SituationStatus;
import com.gyeot.domain.situation.repository.SituationParticipantRepository;
import com.gyeot.domain.situation.repository.SituationRepository;
import com.gyeot.global.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * 테스트 자동 롤백 없이 Writer의 실제 커밋·롤백을 검증합니다.
 * 데이터는 클래스 전용 일회용 MySQL에만 저장하며 각 테스트는 자신이 생성한 ID만 조회합니다.
 * Flyway 스키마를 적용하고 Hibernate validate를 사용합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, SafetyInformationCipher.class,
        SituationCreateWriter.class, SituationCreateWriterIntegrationTest.FixedClockConfig.class})
@ActiveProfiles("test")
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SituationCreateWriterIntegrationTest {
    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 20, 9, 15, 30, 123456000);
    private static final LocalDateTime END = START.plusHours(2);
    private static final LocalDateTime JOINED = LocalDateTime.of(2026, 11, 20, 9, 30, 45, 123456000);
    @Autowired private SituationCreateWriter writer;
    @Autowired private SituationRepository situations;
    @MockitoSpyBean private SituationParticipantRepository participants;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager em;

    @Test
    void commitsSituationAndActiveOwnerWithExactFieldsAndResponse() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        var response = writer.save(10L, "검증된 종류", "회복 지원", START, END);

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(response.situationId()).isNotNull();
        assertThat(response.creatorId()).isEqualTo(10L);
        assertThat(response.status()).isEqualTo(SituationStatus.ACTIVE);
        readInNewTransaction().executeWithoutResult(status -> {
            var situation = situations.findById(response.situationId()).orElseThrow();
            assertThat(situation.getCreatorId()).isEqualTo(10L);
            assertThat(situation.getTitle()).isEqualTo("회복 지원");
            assertThat(situation.getSituationType()).isEqualTo("검증된 종류");
            assertThat(situation.getStartAt()).isEqualTo(START);
            assertThat(situation.getEndAt()).isEqualTo(END);
            assertThat(situation.getStatus()).isEqualTo(SituationStatus.ACTIVE);
            assertThat(situation.getEndedAt()).isNull();
            var owners = em.createQuery("select p from SituationParticipant p where p.situation.id = :id",
                            SituationParticipant.class)
                    .setParameter("id", response.situationId()).getResultList();
            assertThat(owners).hasSize(1);
            var owner = owners.getFirst();
            assertThat(owner.getUserId()).isEqualTo(10L);
            assertThat(owner.getRole()).isEqualTo(SituationParticipantRole.OWNER);
            assertThat(owner.getStatus()).isEqualTo(SituationParticipantStatus.ACTIVE);
            assertThat(owner.getJoinedAt()).isEqualTo(JOINED);
            assertThat(owner.getLeftAt()).isNull();
        });
    }

    @Test
    void otherCustomTextIsPersistedAndReadInSeparateTransaction() {
        var response = writer.save(10L, "OTHER", "기타 상황", START, END, "  재활 동행  ");
        readInNewTransaction().executeWithoutResult(status -> {
            var situation = situations.findById(response.situationId()).orElseThrow();
            assertThat(situation.getSituationType()).isEqualTo("OTHER");
            assertThat(situation.getCustomSituationType()).isEqualTo("재활 동행");
            assertThat(situation.getStartAt()).isEqualTo(START);
            assertThat(situation.getEndAt()).isEqualTo(END);
        });
    }

    @Test
    void ownerSaveFailureRollsBackBothAlreadyFlushedRows() {
        AtomicReference<Long> situationId = new AtomicReference<>();
        AtomicReference<Long> ownerId = new AtomicReference<>();
        var failure = new IllegalStateException("OWNER 저장 단계 실패 주입");
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            // 실제 Repository를 실행하고 두 INSERT를 DB에 반영한 다음 예외를 주입합니다.
            SituationParticipant owner = (SituationParticipant) invocation.callRealMethod();
            em.flush();
            situationId.set(owner.getSituation().getId());
            ownerId.set(owner.getId());
            assertThat(em.createNativeQuery("select count(*) from situations where id = :id", Long.class)
                    .setParameter("id", situationId.get()).getSingleResult()).isEqualTo(1L);
            assertThat(em.createNativeQuery("select count(*) from situation_participants where id = :id", Long.class)
                    .setParameter("id", ownerId.get()).getSingleResult()).isEqualTo(1L);
            throw failure;
        }).when(participants).save(any(SituationParticipant.class));

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(assertThrows(IllegalStateException.class,
                () -> writer.save(20L, "검증된 종류", "롤백 검증", START, END))).isSameAs(failure);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(situationId.get()).isNotNull();
        assertThat(ownerId.get()).isNotNull();

        // Writer 프록시가 롤백을 마친 뒤 독립 트랜잭션에서 실제 DB 부재를 확인합니다.
        readInNewTransaction().executeWithoutResult(status -> {
            assertThat(situations.findById(situationId.get())).isEmpty();
            assertThat(participants.findById(ownerId.get())).isEmpty();
        });
    }

    private TransactionTemplate readInNewTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(true);
        return template;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            // UTC Clock을 주입해도 Writer가 서울 현지 시각으로 변환해야 합니다.
            return Clock.fixed(Instant.parse("2026-11-20T00:30:45.123456789Z"), ZoneOffset.UTC);
        }
    }
}
