package com.gyeot.domain.situation.query;

import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.entity.SituationParticipant;
import com.gyeot.domain.situation.entity.SituationParticipantRole;
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

/** 실제 MySQL에서 저장 후 다시 읽어 JPA 조회와 인터페이스 Bean 주입을 검증합니다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, SafetyInformationCipher.class, SituationParticipantReaderImpl.class})
@ActiveProfiles("test")
@Testcontainers
class SituationParticipantReaderIntegrationTest {
    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    private static final LocalDate START = LocalDate.of(2026, 11, 1);
    private static final LocalDate END = LocalDate.of(2026, 11, 5);
    private static final LocalDateTime JOINED_AT = START.atStartOfDay();

    @Autowired private SituationParticipantReader reader;
    @Autowired private SituationRepository situationRepository;
    @Autowired private SituationParticipantRepository participantRepository;
    @Autowired private EntityManager entityManager;

    @Test
    void ACTIVE_ID는_OWNER를_포함하고_LEFT_REMOVED와_다른_상황은_제외한다() {
        Situation situation = saveSituation(10L);
        saveParticipant(situation, 10L, SituationParticipantRole.OWNER);
        saveParticipant(situation, 20L, SituationParticipantRole.PARTICIPANT);
        saveParticipant(situation, 30L, SituationParticipantRole.PARTICIPANT).leave(JOINED_AT.plusHours(1));
        saveParticipant(situation, 40L, SituationParticipantRole.PARTICIPANT).remove(JOINED_AT.plusHours(1));
        saveParticipant(saveSituation(50L), 60L, SituationParticipantRole.PARTICIPANT);
        flushAndClear();

        assertThat(reader.findActiveParticipantIds(situation.getId())).containsExactly(10L, 20L);
    }

    @Test
    void ACTIVE_ID는_참여_시각_후_PK_순으로_정렬한다() {
        Situation situation = saveSituation(10L);
        // 먼저 저장한 늦은 참여보다 이후 저장한 이른 참여가 앞섭니다.
        participantRepository.save(SituationParticipant.create(situation, 90L,
                SituationParticipantRole.PARTICIPANT, JOINED_AT.plusHours(2)));
        saveParticipant(situation, 30L, SituationParticipantRole.PARTICIPANT);
        saveParticipant(situation, 20L, SituationParticipantRole.PARTICIPANT);
        flushAndClear();

        assertThat(reader.findActiveParticipantIds(situation.getId())).containsExactly(30L, 20L, 90L);
    }

    @Test
    void 상황이_없거나_ACTIVE_참여가_없으면_빈_ID_목록이다() {
        Situation empty = saveSituation(10L);
        Situation inactive = saveSituation(30L);
        saveParticipant(inactive, 40L, SituationParticipantRole.PARTICIPANT).leave(JOINED_AT.plusHours(1));
        flushAndClear();

        assertThat(reader.findActiveParticipantIds(Long.MAX_VALUE)).isEmpty();
        assertThat(reader.findActiveParticipantIds(empty.getId())).isEmpty();
        assertThat(reader.findActiveParticipantIds(inactive.getId())).isEmpty();
    }

    @Test
    void ENDED_상황도_ACTIVE_ID를_반환한다() {
        Situation situation = saveSituation(10L);
        saveParticipant(situation, 10L, SituationParticipantRole.OWNER);
        situation.end(END.atTime(23, 0));
        flushAndClear();

        assertThat(reader.findActiveParticipantIds(situation.getId())).containsExactly(10L);
    }

    @Test
    void 재참여는_LEFT_이력을_제외하고_ACTIVE_ID를_한번만_반환한다() {
        Situation situation = saveSituation(10L);
        saveParticipant(situation, 20L, SituationParticipantRole.PARTICIPANT).leave(JOINED_AT.plusHours(1));
        // 기존 ACTIVE 행의 LEFT 전이를 먼저 반영해 활성 참여 1건 UNIQUE 제약을 지킵니다.
        participantRepository.flush();
        saveParticipant(situation, 20L, SituationParticipantRole.PARTICIPANT);
        flushAndClear();

        assertThat(reader.findActiveParticipantIds(situation.getId())).containsExactly(20L);
    }

    @Test
    void OWNER와_일반_참여자의_ACTIVE_행을_조회한다() {
        Situation situation = saveSituation(10L);
        saveParticipant(situation, 10L, SituationParticipantRole.OWNER);
        saveParticipant(situation, 20L, SituationParticipantRole.PARTICIPANT);
        flushAndClear();

        assertThat(reader.isActiveParticipant(situation.getId(), 10L)).isTrue();
        assertThat(reader.isActiveParticipant(situation.getId(), 20L)).isTrue();
        assertThat(reader.findSituation(situation.getId())).contains(
                new SituationParticipantReader.SituationSummary(10L, START, END, "ACTIVE"));
    }

    @Test
    void 다른_상황과_사용자의_참여를_혼동하지_않는다() {
        Situation first = saveSituation(10L);
        Situation second = saveSituation(30L);
        saveParticipant(first, 20L, SituationParticipantRole.PARTICIPANT);
        saveParticipant(second, 40L, SituationParticipantRole.PARTICIPANT);
        flushAndClear();

        assertThat(reader.isActiveParticipant(first.getId(), 20L)).isTrue();
        assertThat(reader.isActiveParticipant(second.getId(), 40L)).isTrue();
        assertThat(reader.isActiveParticipant(first.getId(), 40L)).isFalse();
        assertThat(reader.isActiveParticipant(second.getId(), 20L)).isFalse();
        assertThat(reader.isActiveParticipant(first.getId(), 99L)).isFalse();
        assertThat(reader.isActiveParticipant(first.getId(), 10L)).isFalse();
    }

    @Test
    void LEFT와_REMOVED_참여는_false이다() {
        Situation situation = saveSituation(10L);
        saveParticipant(situation, 20L, SituationParticipantRole.PARTICIPANT).leave(JOINED_AT.plusHours(1));
        saveParticipant(situation, 30L, SituationParticipantRole.PARTICIPANT).remove(JOINED_AT.plusHours(1));
        flushAndClear();

        assertThat(reader.isActiveParticipant(situation.getId(), 20L)).isFalse();
        assertThat(reader.isActiveParticipant(situation.getId(), 30L)).isFalse();
    }

    @Test
    void 종료된_상황도_조회하고_ACTIVE_참여를_유지한다() {
        Situation situation = saveSituation(10L);
        saveParticipant(situation, 10L, SituationParticipantRole.OWNER);
        saveParticipant(situation, 20L, SituationParticipantRole.PARTICIPANT);
        situation.end(END.atTime(23, 0));
        flushAndClear();

        assertThat(reader.findSituation(situation.getId())).contains(
                new SituationParticipantReader.SituationSummary(10L, START, END, "ENDED"));
        assertThat(reader.isActiveParticipant(situation.getId(), 10L)).isTrue();
        assertThat(reader.isActiveParticipant(situation.getId(), 20L)).isTrue();
    }

    @Test
    void 상황이_없으면_빈_Optional과_false이다() {
        assertThat(reader.findSituation(Long.MAX_VALUE)).isEmpty();
        assertThat(reader.isActiveParticipant(Long.MAX_VALUE, 20L)).isFalse();
    }

    @Test
    void 과거_이탈_행과_새_ACTIVE_행이_함께_있으면_true이다() {
        Situation situation = saveSituation(10L);
        saveParticipant(situation, 20L, SituationParticipantRole.PARTICIPANT).leave(JOINED_AT.plusHours(1));
        participantRepository.flush();
        saveParticipant(situation, 20L, SituationParticipantRole.PARTICIPANT);
        flushAndClear();

        assertThat(reader.isActiveParticipant(situation.getId(), 20L)).isTrue();
    }

    private Situation saveSituation(Long creatorId) {
        return situationRepository.save(Situation.create(creatorId, "돌봄", "병원 동행", START, END));
    }

    private SituationParticipant saveParticipant(Situation situation, Long userId, SituationParticipantRole role) {
        return participantRepository.save(SituationParticipant.create(situation, userId, role, JOINED_AT));
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
