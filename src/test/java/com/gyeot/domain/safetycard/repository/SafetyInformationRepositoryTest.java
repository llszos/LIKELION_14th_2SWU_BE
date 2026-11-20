package com.gyeot.domain.safetycard.repository;

import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import com.gyeot.global.config.JpaAuditingConfig;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 실제 MySQL에서 안심카드 조회 쿼리의 조건을 확인합니다.
 *
 * - 항목 이름 조회(findIdAndTypeByOwnerIdAndIdIn)가 정보 주인 조건으로 다른 사람의 항목을 거르고,
 *   없는 ID와 삭제된 항목을 빼는지 확인합니다. (Mock 테스트로는 쿼리 조건을 확인할 수 없음)
 *
 * @DataJpaTest는 JPA 관련 Bean만 띄우는 가벼운 테스트이고, 각 테스트는 끝나면 자동으로 롤백됩니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // 내장 DB로 바꾸지 않고 아래 MySQL 사용
@Import({JpaAuditingConfig.class, SafetyInformationCipher.class}) // created_at, updated_at 자동 입력과 안심카드 암호화
@ActiveProfiles("test")
@Testcontainers
class SafetyInformationRepositoryTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    private static final Long OWNER_ID = 10L;
    private static final Long OTHER_ID = 11L;
    private static final Long MISSING_ID = 999_999L; // 저장한 적 없는 항목 ID

    @Autowired private SafetyInformationRepository safetyInformationRepository;

    @Test
    void 항목_이름_조회는_내_항목의_ID와_유형만_돌려준다() {
        Long medication = save(OWNER_ID, InformationType.MEDICATION, "혈압약");
        Long allergy = save(OWNER_ID, InformationType.ALLERGY, "땅콩");
        Long othersItem = save(OTHER_ID, InformationType.DISEASE, "당뇨");   // 다른 사람의 항목
        Long deleted = save(OWNER_ID, InformationType.PET, "나롱이");
        safetyInformationRepository.deleteById(deleted);                     // 삭제된 항목
        safetyInformationRepository.flush();                                 // 조회 쿼리 전에 삭제를 DB에 반영

        List<InformationIdAndType> rows = safetyInformationRepository.findIdAndTypeByOwnerIdAndIdIn(
                OWNER_ID, List.of(allergy, othersItem, deleted, MISSING_ID, medication));

        // 내 항목만 ID 오름차순으로 남고, 다른 사람의 항목, 삭제된 항목, 없는 ID는 빠짐
        assertThat(rows)
                .extracting(InformationIdAndType::id, InformationIdAndType::informationType)
                .containsExactly(
                        tuple(medication, InformationType.MEDICATION),
                        tuple(allergy, InformationType.ALLERGY));
    }

    @Test
    void 항목_이름_조회는_다른_사람의_항목만_있으면_빈_목록이다() {
        Long othersItem = save(OTHER_ID, InformationType.MEDICATION, "혈압약");

        assertThat(safetyInformationRepository.findIdAndTypeByOwnerIdAndIdIn(OWNER_ID, List.of(othersItem))).isEmpty();
    }

    private Long save(Long ownerId, InformationType type, String title) {
        return safetyInformationRepository.save(SafetyInformation.create(ownerId, type, title, "내용")).getId();
    }
}
