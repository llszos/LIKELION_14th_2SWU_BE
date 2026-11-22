package com.gyeot.domain.situation.repository;

import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReaderImpl;
import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.entity.SituationParticipant;
import com.gyeot.domain.situation.entity.SituationParticipantRole;
import com.gyeot.domain.situation.entity.SituationParticipantStatus;
import com.gyeot.domain.situation.service.SituationQueryService;
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

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 일회용 MySQL에서 EXISTS 필터·중복 제거·정렬·Slice 경계를 검증합니다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, SafetyInformationCipher.class, CareRequestSummaryReaderImpl.class, SituationQueryService.class, com.gyeot.domain.user.query.UserProfileQueryImpl.class,
        com.gyeot.global.config.ClockConfiguration.class})
@ActiveProfiles("test")
@Testcontainers
class SituationListRepositoryIntegrationTest {
    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 20, 9, 0);
    @Autowired private SituationRepository situations;
    @Autowired private SituationParticipantRepository participants;
    @Autowired private SituationQueryService service;
    @Autowired private EntityManager em;

    @Test
    void includesOnlyActiveSituationsWithActiveParticipationWithoutDuplicates() {
        Situation owner = save(20L);
        participate(owner, 20L, SituationParticipantRole.OWNER, SituationParticipantStatus.ACTIVE);
        Situation active = save(10L);
        participate(active, 20L, SituationParticipantRole.PARTICIPANT, SituationParticipantStatus.ACTIVE);
        participate(active, 20L, SituationParticipantRole.PARTICIPANT, SituationParticipantStatus.REMOVED);
        participate(active, 20L, SituationParticipantRole.PARTICIPANT, SituationParticipantStatus.LEFT);
        Situation left = save(10L);
        participate(left, 20L, SituationParticipantRole.PARTICIPANT, SituationParticipantStatus.LEFT);
        Situation removed = save(10L);
        participate(removed, 20L, SituationParticipantRole.PARTICIPANT, SituationParticipantStatus.REMOVED);
        Situation ended = save(10L);
        participate(ended, 20L, SituationParticipantRole.PARTICIPANT, SituationParticipantStatus.ACTIVE);
        ended.end(START.plusHours(1));
        Situation other = save(10L);
        participate(other, 30L, SituationParticipantRole.PARTICIPANT, SituationParticipantStatus.ACTIVE);
        save(20L); // 생성자여도 ACTIVE 참여 행이 없으면 조회하지 않음
        em.flush();
        em.clear();

        var result = service.listActive(20L, 0, 20);
        assertThat(result.items()).extracting(item -> item.situationId())
                .containsExactlyInAnyOrder(owner.getId(), active.getId());
        assertThat(result.items()).allSatisfy(item -> assertThat(item.participantCount()).isEqualTo(1));
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    void sortsByCreatedAtThenIdAndKeepsSliceBoundariesDespiteDuplicateHistory() {
        Situation first = save(10L);
        Situation second = save(10L);
        Situation oldButHigherId = save(10L);
        participate(first, 20L, SituationParticipantRole.PARTICIPANT, SituationParticipantStatus.ACTIVE);
        participate(second, 20L, SituationParticipantRole.PARTICIPANT, SituationParticipantStatus.ACTIVE);
        participate(second, 20L, SituationParticipantRole.PARTICIPANT, SituationParticipantStatus.LEFT);
        participate(second, 20L, SituationParticipantRole.PARTICIPANT, SituationParticipantStatus.REMOVED);
        participate(oldButHigherId, 20L, SituationParticipantRole.PARTICIPANT, SituationParticipantStatus.ACTIVE);
        em.flush();
        // Auditing 컬럼은 updatable=false이므로 테스트 전용 SQL로 동일 시각 fixture를 고정합니다.
        setCreatedAt(first, START);
        setCreatedAt(second, START);
        setCreatedAt(oldButHigherId, START.minusDays(1));
        em.clear();

        var page0 = service.listActive(20L, 0, 2);
        var page1 = service.listActive(20L, 1, 2);
        var page2 = service.listActive(20L, 2, 2);
        assertThat(page0.items()).extracting(item -> item.situationId()).containsExactly(second.getId(), first.getId());
        assertThat(page0.hasNext()).isTrue();
        assertThat(page1.items()).extracting(item -> item.situationId()).containsExactly(oldButHigherId.getId());
        assertThat(page1.hasNext()).isFalse();
        assertThat(page2.items()).isEmpty();
    }

    private Situation save(Long creatorId) {
        return situations.save(Situation.create(creatorId, "유형", "상황", START, START.plusDays(1)));
    }

    private void participate(Situation situation, Long userId, SituationParticipantRole role,
                             SituationParticipantStatus status) {
        SituationParticipant p = SituationParticipant.create(situation, userId, role, START);
        if (status == SituationParticipantStatus.LEFT) p.leave(START.plusHours(1));
        if (status == SituationParticipantStatus.REMOVED) p.remove(START.plusHours(1));
        participants.save(p);
    }

    private void setCreatedAt(Situation situation, LocalDateTime createdAt) {
        em.createNativeQuery("update situations set created_at = :createdAt where id = :id")
                .setParameter("createdAt", createdAt).setParameter("id", situation.getId()).executeUpdate();
    }
}
