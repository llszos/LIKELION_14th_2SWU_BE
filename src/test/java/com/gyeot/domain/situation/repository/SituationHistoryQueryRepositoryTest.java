package com.gyeot.domain.situation.repository;

import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
import com.gyeot.domain.situation.entity.*;
import com.gyeot.global.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, SafetyInformationCipher.class})
@ActiveProfiles("test")
@Testcontainers
class SituationHistoryQueryRepositoryTest {
    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");
    @Autowired private EntityManager em;
    @Autowired private SituationHistoryQueryRepository repository;
    private static final LocalDateTime ENDED = LocalDateTime.of(2026, 11, 1, 0, 0);

    @Test
    void 종료된_생성자_및_ACTIVE_참여자만_조회하고_여러_이력도_중복되지_않는다() {
        Situation owned = save(10L, ENDED);
        Situation joined = save(20L, ENDED);
        Situation leftOnly = save(20L, ENDED);
        Situation removedOnly = save(20L, ENDED);
        Situation unrelated = save(20L, ENDED);
        Situation active = save(10L, null);
        var old = join(joined, 10L);
        old.leave(ENDED.minusHours(1));
        join(joined, 10L);
        join(leftOnly, 10L).leave(ENDED.minusHours(1));
        join(removedOnly, 10L).remove(ENDED.minusHours(1));
        em.flush(); em.clear();
        var result = repository.findHistory(10L, page(0, 20));
        assertThat(result.getContent()).extracting(Situation::getId)
                .containsExactly(joined.getId(), owned.getId())
                .doesNotContain(leftOnly.getId(), removedOnly.getId(), unrelated.getId(), active.getId());
        assertThat(repository.findHistory(99L, page(0, 20))).isEmpty();
    }

    @Test
    void 상세는_무관한_사용자와_ACTIVE_상황과_없는_상황을_반환하지_않는다() {
        Situation ended = save(10L, ENDED);
        Situation active = save(10L, null);
        join(ended, 20L);
        em.flush(); em.clear();
        assertThat(repository.findAccessibleHistory(ended.getId(), 10L)).isPresent();
        assertThat(repository.findAccessibleHistory(ended.getId(), 20L)).isPresent();
        assertThat(repository.findAccessibleHistory(ended.getId(), 99L)).isEmpty();
        assertThat(repository.findAccessibleHistory(active.getId(), 10L)).isEmpty();
        assertThat(repository.findAccessibleHistory(Long.MAX_VALUE, 10L)).isEmpty();
    }

    @Test
    void 종료시각과_ID_내림차순으로_페이징하고_hasNext를_계산한다() {
        Situation old = save(10L, ENDED.minusDays(1));
        Situation firstTie = save(10L, ENDED);
        Situation secondTie = save(10L, ENDED);
        em.flush(); em.clear();
        var first = repository.findHistory(10L, page(0, 2));
        assertThat(first.getContent()).extracting(Situation::getId).containsExactly(secondTie.getId(), firstTie.getId());
        assertThat(first.hasNext()).isTrue();
        var second = repository.findHistory(10L, page(1, 2));
        assertThat(second.getContent()).extracting(Situation::getId).containsExactly(old.getId());
        assertThat(second.hasNext()).isFalse();
    }

    @Test
    void 상세도_LEFT_REMOVED_이력만_있는_참여자는_허용하지_않는다() {
        Situation leftOnly = save(10L, ENDED);
        Situation removedOnly = save(10L, ENDED);
        join(leftOnly, 20L).leave(ENDED.minusHours(1));
        join(removedOnly, 20L).remove(ENDED.minusHours(1));
        em.flush(); em.clear();
        assertThat(repository.findAccessibleHistory(leftOnly.getId(), 20L)).isEmpty();
        assertThat(repository.findAccessibleHistory(removedOnly.getId(), 20L)).isEmpty();
        assertThat(repository.findAccessibleHistory(leftOnly.getId(), 10L)).isPresent();
    }

    @Test
    void 상세는_예정_종료일이_아닌_ENDED_상태를_사용하고_시각을_그대로_읽는다() {
        LocalDateTime start = LocalDateTime.of(2026, 12, 31, 23, 59, 59, 123456000);
        LocalDateTime end = LocalDateTime.of(2027, 1, 1, 0, 0);
        LocalDateTime actual = start.plusNanos(1000);
        Situation ended = Situation.create(10L, "돌봄", "연말 기록", start, end);
        ended.end(actual);
        em.persist(ended);
        Situation activePastPeriod = Situation.create(10L, "돌봄", "미처리 상황", start.minusYears(1), end.minusYears(1));
        em.persist(activePastPeriod);
        em.flush(); em.clear();

        Situation found = repository.findAccessibleHistory(ended.getId(), 10L).orElseThrow();
        assertThat(found.getStartAt()).isEqualTo(start);
        assertThat(found.getEndAt()).isEqualTo(end);
        assertThat(found.getEndedAt()).isEqualTo(actual);
        assertThat(repository.findAccessibleHistory(activePastPeriod.getId(), 10L)).isEmpty();
        assertThat(em.find(Situation.class, activePastPeriod.getId()).getStatus()).isEqualTo(SituationStatus.ACTIVE);
    }

    private PageRequest page(int page, int size) {
        return PageRequest.of(page, size, Sort.by(Sort.Order.desc("endedAt"), Sort.Order.desc("id")));
    }

    private Situation save(Long creatorId, LocalDateTime endedAt) {
        Situation situation = Situation.create(creatorId, "돌봄", "지난 상황",
                LocalDateTime.of(2026, 10, 1, 0, 0), LocalDateTime.of(2026, 11, 1, 0, 0));
        if (endedAt != null) situation.end(endedAt);
        em.persist(situation);
        return situation;
    }

    private SituationParticipant join(Situation situation, Long userId) {
        var participant = SituationParticipant.create(situation, userId, SituationParticipantRole.PARTICIPANT, ENDED.minusDays(3));
        em.persist(participant);
        return participant;
    }
}
