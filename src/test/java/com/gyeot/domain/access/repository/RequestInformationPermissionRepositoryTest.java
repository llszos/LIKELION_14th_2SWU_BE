package com.gyeot.domain.access.repository;

import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import com.gyeot.domain.safetycard.repository.SafetyInformationRepository;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 MySQL에서 공유 관리(#46)용 권한 조회 쿼리의 조건을 확인합니다. (Mock 테스트로는 쿼리 조건을 확인할 수 없음)
 *
 * - 공유 카드 후보(findSharingCandidateAssignmentIds): 정보 주인 조건, ACTIVE나 직접 철회만, 삭제 항목 제외, 지정 ID 중복 없음
 * - 지정별 권한 이력(findItemPermissionsByAssignmentIds): 상태와 상관없이 모두, 삭제 항목 제외, 다른 지정 제외, ID 오름차순
 * - 지정 → 요청 ID(findRequestIdByAssignmentId): 권한이 여러 행이어도 하나, 권한이 없는 지정이면 빈 값
 *
 * 권한과 항목만 저장합니다. 권한 테이블은 요청과 지정을 ID 값으로만 들고 있어(연관관계 매핑 없음) 요청, 지정 행 없이 확인할 수 있습니다.
 * @DataJpaTest는 JPA 관련 Bean만 띄우는 가벼운 테스트이고, 각 테스트는 끝나면 자동으로 롤백됩니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // 내장 DB로 바꾸지 않고 아래 MySQL 사용
@Import({JpaAuditingConfig.class, SafetyInformationCipher.class}) // created_at 자동 입력과 안심카드 암호화
@ActiveProfiles("test")
@Testcontainers
class RequestInformationPermissionRepositoryTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    private static final Long OWNER_ID = 10L;   // 정보 주인 (요청자)
    private static final Long OTHER_ID = 11L;   // 다른 사람
    private static final Long REQUEST_ID = 31L; // 모든 권한이 같은 요청 (지정별 조회와 요청 ID 조회 확인용)
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 2, 15, 0);

    @Autowired private RequestInformationPermissionRepository permissionRepository;
    @Autowired private SafetyInformationRepository safetyInformationRepository;

    // === 공유 카드 후보 ===

    @Test
    void 카드_후보는_정보_주인_항목의_ACTIVE나_직접_철회가_있는_지정이고_중복이_없다() {
        Long item = saveItem(OWNER_ID, "혈압약");
        Long othersItem = saveItem(OTHER_ID, "당뇨약");
        Long deletedItem = saveItem(OWNER_ID, "땅콩");

        // 40: 직접 철회 → 다시 허용(새 ACTIVE 행). 같은 지정에 후보 조건을 만족하는 행이 2개여도 한 번만
        saveRevoked(40L, item, RevokeReason.OWNER_REVOKED);
        saveActive(40L, item);
        saveRevoked(41L, item, RevokeReason.OWNER_REVOKED);        // 41: 직접 철회만 남음 → 후보 (철회됨 카드)
        permissionRepository.save(pending(42L, item));             // 42: 수락 전 → 후보 아님
        saveRevoked(43L, item, RevokeReason.REQUEST_ENDED);        // 43: 요청 종료로 끝남 → 후보 아님
        saveActive(44L, othersItem);                               // 44: 다른 사람의 항목 → 후보 아님
        saveActive(45L, deletedItem);                              // 45: 공유 중이던 항목을 삭제 → 후보 아님
        deleteItem(deletedItem);

        assertThat(permissionRepository.findSharingCandidateAssignmentIds(OWNER_ID))
                .containsExactlyInAnyOrder(40L, 41L); // distinct라 40은 한 번
    }

    @Test
    void 다른_사람의_항목만_공유했으면_후보가_없다() {
        saveActive(40L, saveItem(OTHER_ID, "당뇨약"));

        assertThat(permissionRepository.findSharingCandidateAssignmentIds(OWNER_ID)).isEmpty();
    }

    // === 지정별 권한 이력 ===

    @Test
    void 지정별_권한_이력은_상태와_상관없이_모두_ID_오름차순이고_삭제_항목과_다른_지정은_뺀다() {
        Long item = saveItem(OWNER_ID, "혈압약");
        Long other = saveItem(OWNER_ID, "당뇨약");
        Long deletedItem = saveItem(OWNER_ID, "땅콩");

        Long first = saveRevoked(40L, item, RevokeReason.OWNER_REVOKED).getId();    // 끝난 권한도 포함 (최신 권한 판단용)
        Long second = saveRevoked(41L, other, RevokeReason.SHARING_EXCLUDED).getId();
        Long third = saveActive(40L, item).getId();                                 // 다시 허용으로 만든 새 행
        saveActive(40L, deletedItem);                                                // 삭제 항목 → information_id NULL이라 빠짐
        saveActive(50L, item);                                                       // 묻지 않은 지정 → 빠짐
        deleteItem(deletedItem);

        assertThat(permissionRepository.findItemPermissionsByAssignmentIds(List.of(40L, 41L)))
                .extracting(RequestInformationPermission::getId)
                .containsExactly(first, second, third); // 같은 항목은 나중에 만든 행이 뒤 (최신 판단의 전제)
    }

    // === 지정 → 요청 ID ===

    @Test
    void 지정의_권한이_여러_행이어도_요청_ID는_하나다() {
        Long item = saveItem(OWNER_ID, "혈압약");
        Long other = saveItem(OWNER_ID, "당뇨약");
        saveRevoked(40L, item, RevokeReason.OWNER_REVOKED);
        saveActive(40L, item);
        saveActive(40L, other);

        assertThat(permissionRepository.findRequestIdByAssignmentId(40L)).contains(REQUEST_ID);
    }

    @Test
    void 권한이_없는_지정이면_요청_ID가_없다() {
        saveActive(40L, saveItem(OWNER_ID, "혈압약"));

        assertThat(permissionRepository.findRequestIdByAssignmentId(99L)).isEmpty();
    }

    // === 도우미 ===

    /** 안심카드 항목을 저장하고 ID를 돌려줌 (권한의 정보 주인 조건은 이 항목의 주인으로 판단) */
    private Long saveItem(Long ownerId, String title) {
        return safetyInformationRepository.save(
                SafetyInformation.create(ownerId, InformationType.MEDICATION, title, "아침 1정")).getId();
    }

    /** 조회 조건 검증용: 권한의 information_id를 비우고 원본 삭제. 실제 삭제 API의 회수와 이력 처리는 생략합니다. */
    private void deleteItem(Long informationId) {
        permissionRepository.clearInformationId(informationId);
        safetyInformationRepository.deleteById(informationId);
        safetyInformationRepository.flush(); // 조회 쿼리 전에 삭제를 DB에 반영
    }

    /** 공유 중(ACTIVE) 권한 저장 (다시 허용도 같은 모양의 새 행) */
    private RequestInformationPermission saveActive(Long assignmentId, Long informationId) {
        return permissionRepository.save(RequestInformationPermission.createActive(
                REQUEST_ID, assignmentId, informationId, InformationType.MEDICATION, NOW.minusDays(1), null));
    }

    /** 활성화된 뒤 끝난(REVOKED) 권한 저장 (사유로 직접 철회와 그 밖을 구분) */
    private RequestInformationPermission saveRevoked(Long assignmentId, Long informationId, RevokeReason reason) {
        RequestInformationPermission permission = RequestInformationPermission.createActive(
                REQUEST_ID, assignmentId, informationId, InformationType.MEDICATION, NOW.minusDays(1), null);
        permission.revoke(reason, NOW.minusHours(1));
        return permissionRepository.save(permission);
    }

    /** 수락 전(PENDING) 권한 (저장은 호출한 쪽에서) */
    private static RequestInformationPermission pending(Long assignmentId, Long informationId) {
        return RequestInformationPermission.createPending(REQUEST_ID, assignmentId, informationId, InformationType.MEDICATION);
    }
}
