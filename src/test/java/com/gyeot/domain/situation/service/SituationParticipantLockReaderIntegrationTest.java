package com.gyeot.domain.situation.service;

import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
import com.gyeot.domain.situation.entity.*;
import com.gyeot.domain.situation.repository.SituationParticipantRepository;
import com.gyeot.domain.situation.repository.SituationRepository;
import com.gyeot.global.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 일회용 MySQL 전용. 조회 필터 검증이며 동시 트랜잭션의 잠금 경합 검증은 아닙니다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, SafetyInformationCipher.class, SituationParticipantLockReader.class})
@ActiveProfiles("test")
@Testcontainers
class SituationParticipantLockReaderIntegrationTest {
    @Container @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");
    @Autowired SituationRepository situations;
    @Autowired SituationParticipantRepository participants;
    @Autowired SituationParticipantLockReader reader;
    @Autowired EntityManager em;

    @Test
    void matchesBothSituationAndParticipationPrimaryKeyWithoutChangingLeaveLookup() {
        var target = saveSituation();
        var other = saveSituation();
        var participant = participants.save(SituationParticipant.create(target, 20L,
                SituationParticipantRole.PARTICIPANT, LocalDateTime.of(2026, 11, 15, 9, 0)));
        Long id = participant.getId();
        em.flush();
        em.clear();

        assertThat(reader.findForUpdate(target.getId(), id)).hasValueSatisfying(p -> {
            assertThat(p.getId()).isEqualTo(id);
            assertThat(p.getUserId()).isEqualTo(20L);
        });
        assertThat(reader.findForUpdate(other.getId(), id)).isEmpty();
        assertThat(participants.findHistoryForUpdate(target.getId(), 20L))
                .extracting(SituationParticipant::getId).containsExactly(id);
    }

    @Test
    void missingParticipationOrSituationReturnsEmpty() {
        var situation = saveSituation();
        em.flush();
        em.clear();
        assertThat(reader.findForUpdate(situation.getId(), Long.MAX_VALUE)).isEmpty();
        assertThat(reader.findForUpdate(Long.MAX_VALUE, Long.MAX_VALUE)).isEmpty();
    }

    private Situation saveSituation() {
        return situations.save(Situation.create(10L, "유형", "이름",
                LocalDate.of(2026, 11, 15), LocalDate.of(2026, 11, 20)));
    }
}
