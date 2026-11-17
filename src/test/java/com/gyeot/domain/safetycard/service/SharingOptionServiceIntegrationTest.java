package com.gyeot.domain.safetycard.service;

import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.query.CareRequestSharingReaderImpl;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
import com.gyeot.domain.safetycard.dto.response.SharingOptionsResponse;
import com.gyeot.domain.safetycard.dto.response.SharingOptionsResponse.ItemOption;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import com.gyeot.domain.safetycard.repository.SafetyInformationRepository;
import com.gyeot.global.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
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
 * 실제 MySQL에 C의 요청, 지정과 D의 항목, 권한을 저장하고 공개 항목 선택지의 선택값을 확인합니다. (#138)
 *
 * - 마지막 대상 지정은 C의 assignment_order 기준입니다. 권한 테이블의 지정 ID 최댓값으로 찾으면
 *   마지막 지정에 공개 항목이 0개일 때(권한 행 없음) 이전 지정의 항목을 잘못 선택합니다.
 * - 지정 ID(자동 증가) 순서와 assignment_order 순서가 다르게 저장해, ID 순서로는 통과하지 못하게 합니다.
 * - C Reader는 Mock이 아니라 실제 구현체를 씁니다. 지정 순서 판단까지 함께 확인하기 위해서입니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, SafetyInformationCipher.class, SharingOptionService.class, CareRequestSharingReaderImpl.class})
@ActiveProfiles("test")
@Testcontainers
class SharingOptionServiceIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    private static final Long SITUATION_ID = 1L;
    private static final Long OWNER_ID = 10L;        // 요청자 = 정보 주인
    private static final Long FIRST_TARGET_ID = 20L; // 처음 부탁한 대상
    private static final Long NEXT_TARGET_ID = 30L;  // 다시 부탁한 대상
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 9, 0);

    @Autowired private SharingOptionService service;
    @Autowired private CareRequestRepository careRequestRepository;
    @Autowired private RequestAssignmentRepository assignmentRepository;
    @Autowired private SafetyInformationRepository safetyInformationRepository;
    @Autowired private RequestInformationPermissionRepository permissionRepository;
    @Autowired private EntityManager entityManager;

    private CareRequest request;
    private Long medicationId; // 복용약 (병원 동행 기본 유형)
    private Long allergyId;    // 알러지 (병원 동행 기본 유형)
    private Long addressId;    // 주소 (병원 동행 기본 유형 아님)

    @BeforeEach
    void setUp() {
        medicationId = saveItem(InformationType.MEDICATION, "혈압약", "매일 1정");
        allergyId = saveItem(InformationType.ALLERGY, "페니실린", "두드러기 반응");
        addressId = saveItem(InformationType.ADDRESS, null, "서울시 노원구");
        request = careRequestRepository.save(CareRequest.createGeneral(SITUATION_ID, OWNER_ID, HelpType.HOSPITAL,
                "병원 동행 부탁드려요", "○○병원", NOW.plusDays(1), NOW.plusDays(1).plusHours(3), null));
    }

    // === 마지막 대상 지정 기준 (selected) ===

    @Test
    void 마지막_지정의_공개_항목이_0개면_이전_지정의_항목을_선택하지_않는다() {
        // 처음 대상에게 복용약, 알러지를 공개했지만 거절 → 다시 부탁하면서 공개 항목 0개 (권한 행 없음)
        RequestAssignment first = closedAssignment(FIRST_TARGET_ID, 1);
        endedPermission(first, medicationId, InformationType.MEDICATION);
        endedPermission(first, allergyId, InformationType.ALLERGY);
        openAssignment(NEXT_TARGET_ID, 2);
        flushAndClear();

        assertThat(selectedIds(options())).isEmpty();
    }

    @Test
    void 지정_ID가_아니라_assignment_order로_마지막_지정을_고른다() {
        // 다시 부탁한 지정(order 2)을 먼저 저장해 ID가 더 작게 만듦
        RequestAssignment second = openAssignment(NEXT_TARGET_ID, 2);
        RequestAssignment first = closedAssignment(FIRST_TARGET_ID, 1);
        endedPermission(first, medicationId, InformationType.MEDICATION);
        endedPermission(first, allergyId, InformationType.ALLERGY);
        pendingPermission(second, addressId, InformationType.ADDRESS);
        flushAndClear();

        assertThat(first.getId()).isGreaterThan(second.getId()); // 준비 확인: ID는 처음 지정이 더 큼
        assertThat(selectedIds(options())).containsExactly(addressId);
    }

    @Test
    void 직접_철회한_항목은_선택하지_않고_다시_허용하면_선택한다() {
        RequestAssignment assignment = openAssignment(FIRST_TARGET_ID, 1);
        // 복용약: 공유 → 직접 철회 (최신 행이 OWNER_REVOKED)
        RequestInformationPermission revoked = activePermission(assignment, medicationId, InformationType.MEDICATION, NOW);
        revoked.revoke(RevokeReason.OWNER_REVOKED, NOW.plusHours(1));
        // 알러지: 공유 → 직접 철회 → 다시 허용 (최신 행이 새 ACTIVE)
        activePermission(assignment, allergyId, InformationType.ALLERGY, NOW)
                .revoke(RevokeReason.OWNER_REVOKED, NOW.plusHours(1));
        activePermission(assignment, allergyId, InformationType.ALLERGY, NOW.plusHours(2));
        flushAndClear();

        assertThat(selectedIds(options())).containsExactly(allergyId);
    }

    // === 준비 코드 ===

    private SharingOptionsResponse options() {
        return service.getSharingOptions(OWNER_ID, HelpType.HOSPITAL, request.getId());
    }

    private List<Long> selectedIds(SharingOptionsResponse response) {
        return response.categories().stream()
                .flatMap(category -> category.items().stream())
                .filter(ItemOption::selected)
                .map(ItemOption::informationId)
                .toList();
    }

    private Long saveItem(InformationType type, String title, String value) {
        return safetyInformationRepository.save(SafetyInformation.create(OWNER_ID, type, title, value)).getId();
    }

    /** 응답을 기다리는 지금 대상 (열린 지정은 요청당 1건) */
    private RequestAssignment openAssignment(Long targetUserId, int order) {
        return assignmentRepository.save(RequestAssignment.assign(request, targetUserId, order, NOW.plusHours(12)));
    }

    /** 어려움으로 답한 뒤 다른 사람에게 넘어가 마감된 지정 */
    private RequestAssignment closedAssignment(Long targetUserId, int order) {
        RequestAssignment assignment = RequestAssignment.assign(request, targetUserId, order, NOW.plusHours(12));
        assignment.respond(AssignmentResponseStatus.UNAVAILABLE, NOW);
        assignment.close(AssignmentCloseReason.TARGET_CHANGED, NOW);
        return assignmentRepository.save(assignment);
    }

    /** 수락 전에 공개했던 권한 (PENDING) */
    private RequestInformationPermission pendingPermission(RequestAssignment assignment, Long informationId, InformationType type) {
        return permissionRepository.save(RequestInformationPermission.createPending(
                request.getId(), assignment.getId(), informationId, type));
    }

    /** 수락 전에 공개했지만 대상이 바뀌어 끝난 권한 (PENDING → REVOKED, 부여 시각 없음) */
    private RequestInformationPermission endedPermission(RequestAssignment assignment, Long informationId, InformationType type) {
        RequestInformationPermission permission = RequestInformationPermission.createPending(
                request.getId(), assignment.getId(), informationId, type);
        permission.revoke(RevokeReason.ASSIGNMENT_CLOSED, NOW);
        return permissionRepository.save(permission);
    }

    /** 수락 뒤 공유 중인 권한 (ACTIVE) */
    private RequestInformationPermission activePermission(RequestAssignment assignment, Long informationId,
                                                          InformationType type, LocalDateTime grantedAt) {
        return permissionRepository.save(RequestInformationPermission.createActive(
                request.getId(), assignment.getId(), informationId, type, grantedAt, null));
    }

    /** DB에 반영하고 JPA 메모리를 비워서 조회가 진짜 DB를 거치게 합니다. */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
