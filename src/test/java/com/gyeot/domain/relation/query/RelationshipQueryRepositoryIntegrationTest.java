package com.gyeot.domain.relation.query;

import com.gyeot.domain.relation.entity.Relationship;
import com.gyeot.domain.relation.entity.RelationshipStatus;
import com.gyeot.domain.relation.entity.RelationshipType;
import com.gyeot.domain.relation.repository.RelationshipQueryRepository;
import com.gyeot.domain.relation.repository.RelationshipRepository;
import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 MySQL에서 방향 및 ACTIVE 상태 조건과 요약 매핑을 확인합니다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, SafetyInformationCipher.class, RelationshipQueryImpl.class})
@ActiveProfiles("test")
@Testcontainers
class RelationshipQueryRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    private static final Long SOURCE_ID = 10L;
    private static final Long ACTIVE_TARGET_ID = 20L;
    private static final Long ENDED_TARGET_ID = 30L;
    private static final Long REVERSE_ONLY_TARGET_ID = 40L;
    private static final LocalDateTime ENDED_AT = LocalDateTime.of(2026, 11, 16, 9, 0);

    @Autowired private RelationshipQuery relationshipQuery;
    @Autowired private RelationshipQueryRepository relationshipQueryRepository;
    @Autowired private RelationshipRepository relationshipRepository;
    @Autowired private EntityManager entityManager;

    @Test
    void 실제_MySQL에서_요청한_방향의_ACTIVE_관계만_표시명과_함께_조회한다() {
        relationshipRepository.save(Relationship.createMyPerson(SOURCE_ID, ACTIVE_TARGET_ID, "내가 지정한 이름"));
        Relationship ended = Relationship.createMyPerson(SOURCE_ID, ENDED_TARGET_ID, "종료된 관계");
        ended.end(ENDED_AT);
        relationshipRepository.save(ended);
        relationshipRepository.save(Relationship.createMyPerson(REVERSE_ONLY_TARGET_ID, SOURCE_ID, "상대가 지정한 이름"));
        relationshipRepository.flush();
        entityManager.clear();

        List<RelationshipQuery.RelationshipSummary> result = relationshipQuery.findActiveRelationships(
                SOURCE_ID, List.of(ACTIVE_TARGET_ID, ENDED_TARGET_ID, REVERSE_ONLY_TARGET_ID));

        assertThat(result).containsExactly(new RelationshipQuery.RelationshipSummary(
                ACTIVE_TARGET_ID, RelationshipType.MY_PERSON, "내가 지정한 이름"));
        assertThat(relationshipQueryRepository.findAllBySourceUserIdAndTargetUserIdInAndStatus(
                SOURCE_ID, List.of(ACTIVE_TARGET_ID, ENDED_TARGET_ID, REVERSE_ONLY_TARGET_ID),
                RelationshipStatus.ACTIVE))
                .extracting(Relationship::getTargetUserId)
                .containsExactly(ACTIVE_TARGET_ID);
    }
}
