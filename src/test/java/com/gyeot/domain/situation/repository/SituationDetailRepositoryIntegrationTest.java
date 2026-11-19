package com.gyeot.domain.situation.repository;

import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.entity.SituationParticipant;
import com.gyeot.domain.situation.entity.SituationParticipantRole;
import com.gyeot.domain.situation.entity.SituationParticipantStatus;
import com.gyeot.domain.situation.entity.SituationStatus;
import com.gyeot.domain.situation.exception.SituationQueryErrorCode;
import com.gyeot.domain.situation.service.SituationQueryService;
import com.gyeot.global.config.JpaAuditingConfig;
import com.gyeot.global.exception.BusinessException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 일회용 MySQL의 실제 Repository로 상세 접근 조건을 검증합니다. HTTP 테스트는 아닙니다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, SafetyInformationCipher.class, SituationQueryService.class,
        com.gyeot.global.config.ClockConfiguration.class})
@ActiveProfiles("test")
@Testcontainers
class SituationDetailRepositoryIntegrationTest {
    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 20, 9, 15, 30);
    @Autowired private SituationRepository situations;
    @Autowired private SituationParticipantRepository participants;
    @Autowired private SituationQueryService service;
    @Autowired private EntityManager em;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.gyeot.domain.carerequest.query.CareRequestSummaryReader requestReader;

    @org.junit.jupiter.api.BeforeEach
    void emptyCRequests() {
        org.mockito.Mockito.when(requestReader.getSituationStats(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
                    java.util.List<Long> ids = invocation.getArgument(0);
                    return java.util.Map.of(ids.getFirst(),
                            com.gyeot.domain.carerequest.query.CareRequestSummaryReader.SituationRequestStats.empty());
                });
        org.mockito.Mockito.when(requestReader.getSituationRequests(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of());
    }

    @Test
    void allowsActiveOwnerAndMapsPersistedFields() {
        Situation situation = saveSituation();
        participate(situation, 10L, SituationParticipantRole.OWNER, SituationParticipantStatus.ACTIVE);
        flushAndClear();

        var response = service.detailActive(situation.getId(), 10L);

        assertThat(response.situationId()).isEqualTo(situation.getId());
        assertThat(response.name()).isEqualTo("상세 이름");
        assertThat(response.situationType()).isEqualTo("유형");
        assertThat(response.startAt()).isEqualTo(START);
        assertThat(response.endAt()).isEqualTo(START.plusHours(2));
        assertThat(response.status()).isEqualTo(SituationStatus.ACTIVE);
        assertThat(response.creatorId()).isEqualTo(10L);
        assertThat(response.endedAt()).isNull();
    }

    @Test
    void allowsActiveOrdinaryParticipant() {
        Situation situation = saveSituation();
        participate(situation, 20L, SituationParticipantRole.PARTICIPANT, SituationParticipantStatus.ACTIVE);
        flushAndClear();
        assertThat(service.detailActive(situation.getId(), 20L).situationId()).isEqualTo(situation.getId());
    }

    @Test
    void rejectsUnrelatedUserEvenWhenAnotherUserIsActive() {
        Situation situation = saveSituation();
        participate(situation, 20L, SituationParticipantRole.PARTICIPANT, SituationParticipantStatus.ACTIVE);
        flushAndClear();
        assertNotFound(situation.getId(), 99L);
    }

    @Test
    void rejectsLeftParticipant() {
        Situation situation = saveSituation();
        participate(situation, 20L, SituationParticipantRole.PARTICIPANT, SituationParticipantStatus.LEFT);
        flushAndClear();
        assertNotFound(situation.getId(), 20L);
    }

    @Test
    void rejectsRemovedParticipant() {
        Situation situation = saveSituation();
        participate(situation, 20L, SituationParticipantRole.PARTICIPANT, SituationParticipantStatus.REMOVED);
        flushAndClear();
        assertNotFound(situation.getId(), 20L);
    }

    @Test
    void rejectsEndedSituationDespiteActiveOwnerAndParticipant() {
        Situation situation = saveSituation();
        participate(situation, 10L, SituationParticipantRole.OWNER, SituationParticipantStatus.ACTIVE);
        participate(situation, 20L, SituationParticipantRole.PARTICIPANT, SituationParticipantStatus.ACTIVE);
        situation.end(START.plusHours(1));
        flushAndClear();
        assertNotFound(situation.getId(), 10L);
        assertNotFound(situation.getId(), 20L);
    }

    @Test
    void rejectsMissingSituation() {
        flushAndClear();
        assertThat(situations.findById(Long.MAX_VALUE)).isEmpty();
        assertNotFound(Long.MAX_VALUE, 10L);
    }

    @Test
    void creatorIdentityDoesNotBypassMissingParticipation() {
        Situation situation = saveSituation();
        flushAndClear();
        assertNotFound(situation.getId(), 10L);
    }

    @Test
    void participationInAnotherSituationDoesNotGrantAccess() {
        Situation target = saveSituation();
        Situation other = saveSituation();
        participate(other, 20L, SituationParticipantRole.PARTICIPANT, SituationParticipantStatus.ACTIVE);
        flushAndClear();
        assertNotFound(target.getId(), 20L);
    }

    private Situation saveSituation() {
        return situations.save(Situation.create(10L, "유형", "상세 이름", START, START.plusHours(2)));
    }

    private void participate(Situation situation, Long userId, SituationParticipantRole role,
                             SituationParticipantStatus status) {
        SituationParticipant participant = SituationParticipant.create(situation, userId, role, START);
        if (status == SituationParticipantStatus.LEFT) participant.leave(START.plusMinutes(30));
        if (status == SituationParticipantStatus.REMOVED) participant.remove(START.plusMinutes(30));
        participants.save(participant);
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    private void assertNotFound(Long situationId, Long userId) {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.detailActive(situationId, userId));
        assertThat(error.getErrorCode()).isEqualTo(SituationQueryErrorCode.SITUATION_NOT_FOUND);
        assertThat(error.getErrorCode().getCode()).isEqualTo("SITUATION_NOT_FOUND");
        assertThat(error.getErrorCode().getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
