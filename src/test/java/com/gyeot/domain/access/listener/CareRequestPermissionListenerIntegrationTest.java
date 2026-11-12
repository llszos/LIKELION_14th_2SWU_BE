package com.gyeot.domain.access.listener;

import com.gyeot.domain.access.entity.AccessEventType;
import com.gyeot.domain.access.entity.InformationAccessLog;
import com.gyeot.domain.access.entity.PermissionStatus;
import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.access.repository.InformationAccessLogRepository;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.ChangeableField;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.RequestType;
import com.gyeot.domain.carerequest.event.CareRequestAcceptedEvent;
import com.gyeot.domain.carerequest.event.CareRequestChangeConfirmedEvent;
import com.gyeot.domain.carerequest.event.CareRequestCreatedEvent;
import com.gyeot.domain.carerequest.event.CareRequestTargetChangedEvent;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader.SharingRequestInfo;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import com.gyeot.domain.safetycard.repository.SafetyInformationRepository;
import com.gyeot.global.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

/**
 * C 요청 이벤트 → 리스너 → 권한 서비스 → DB까지 실제로 이어지는지 확인합니다. (#42)
 *
 * - C의 이벤트 발행 코드가 아직 없어, 테스트가 C 대신 트랜잭션을 열고 이벤트를 직접 발행합니다.
 * - 실제 MySQL과 전체 Spring 앱을 띄웁니다. 각 테스트는 커밋되므로 끝나면 직접 지웁니다.
 * - C의 공유용 요청 조회(CareRequestSharingReader)는 요청 데이터를 만들지 않으려고 가짜로 바꿉니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class CareRequestPermissionListenerIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    private static final Long REQUEST_ID = 1L;
    private static final Long OWNER_ID = 10L;    // 요청자 = 정보 주인
    private static final Long TARGET_ID = 20L;   // 대상자, 수락 후 담당자
    private static final Long NEXT_TARGET_ID = 21L;
    private static final Long ASSIGNMENT_ID = 7L;
    private static final Long NEXT_ASSIGNMENT_ID = 8L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 11, 12, 10, 0);
    private static final LocalDateTime ACCEPTED_AT = CREATED_AT.plusHours(1);
    private static final LocalDateTime CHANGED_AT = CREATED_AT.plusHours(2);

    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private SafetyInformationRepository safetyInformationRepository;
    @Autowired private RequestInformationPermissionRepository permissionRepository;
    @Autowired private InformationAccessLogRepository accessLogRepository;

    @MockitoBean private CareRequestSharingReader careRequestSharingReader;

    private Long allergyId;
    private Long medicationId;
    private Long petId;

    @BeforeEach
    void setUp() {
        allergyId = saveItem(InformationType.ALLERGY);
        medicationId = saveItem(InformationType.MEDICATION);
        petId = saveItem(InformationType.PET);
    }

    @AfterEach
    void cleanUp() {
        accessLogRepository.deleteAll();
        permissionRepository.deleteAll();
        safetyInformationRepository.deleteAll();
    }

    @Test
    void 요청_생성과_수락_이벤트로_권한이_만들어지고_활성화된다() {
        publish(created(List.of(allergyId, medicationId)));

        assertThat(permissionsOf(ASSIGNMENT_ID))
                .extracting(RequestInformationPermission::getInformationId, RequestInformationPermission::getStatus)
                .containsExactlyInAnyOrder(tuple(allergyId, PermissionStatus.PENDING), tuple(medicationId, PermissionStatus.PENDING));
        assertThat(accessLogRepository.findAll()).isEmpty(); // 수락 전에는 이력 없음

        publish(accepted(RequestType.GENERAL));

        assertThat(permissionsOf(ASSIGNMENT_ID)).allSatisfy(permission -> {
            assertThat(permission.getStatus()).isEqualTo(PermissionStatus.ACTIVE);
            assertThat(permission.getGrantedAt()).isEqualTo(ACCEPTED_AT);
            assertThat(permission.getExpiresAt()).isNull();
        });
        assertThat(accessLogRepository.findAll()).hasSize(2).allSatisfy(log -> {
            assertThat(log.getEventType()).isEqualTo(AccessEventType.GRANTED);
            assertThat(log.getOwnerUserId()).isEqualTo(OWNER_ID);
            assertThat(log.getActorUserId()).isEqualTo(TARGET_ID);
        });
    }

    @Test
    void 같은_이벤트가_두_번_와도_권한과_이력이_늘지_않는다() {
        publish(created(List.of(allergyId, medicationId)));
        publish(created(List.of(allergyId, medicationId)));
        publish(accepted(RequestType.URGENT));
        publish(accepted(RequestType.URGENT));

        assertThat(permissionsOf(ASSIGNMENT_ID)).hasSize(2).allSatisfy(permission ->
                assertThat(permission.getExpiresAt()).isEqualTo(ACCEPTED_AT.plusHours(24))); // 기한도 연장되지 않음
        assertThat(accessLogRepository.findAll()).hasSize(2);
    }

    @Test
    void 공개_항목이_0개인_요청은_권한을_만들지_않는다() {
        publish(created(List.of()));
        publish(accepted(RequestType.GENERAL));

        assertThat(permissionRepository.findAll()).isEmpty();
        assertThat(accessLogRepository.findAll()).isEmpty();
    }

    @Test
    void 대상_변경은_이전_지정을_회수하고_새_지정에_이벤트_항목으로_만든다() {
        publish(created(List.of(allergyId, medicationId)));

        // 다시 부탁하기 화면에서 공개 항목을 알레르기, 반려동물로 바꿈
        publish(new CareRequestTargetChangedEvent(REQUEST_ID, OWNER_ID, RequestType.GENERAL,
                ASSIGNMENT_ID, TARGET_ID, NEXT_ASSIGNMENT_ID, NEXT_TARGET_ID, List.of(allergyId, petId), CHANGED_AT));

        assertThat(permissionsOf(ASSIGNMENT_ID)).allSatisfy(permission -> {
            assertThat(permission.getStatus()).isEqualTo(PermissionStatus.REVOKED);
            assertThat(permission.getRevokeReason()).isEqualTo(RevokeReason.ASSIGNMENT_CLOSED);
            assertThat(permission.getRevokedAt()).isEqualTo(CHANGED_AT);
        });
        assertThat(permissionsOf(NEXT_ASSIGNMENT_ID))
                .extracting(RequestInformationPermission::getInformationId, RequestInformationPermission::getStatus)
                .containsExactlyInAnyOrder(tuple(allergyId, PermissionStatus.PENDING), tuple(petId, PermissionStatus.PENDING));
        assertThat(accessLogRepository.findAll()).isEmpty(); // 이전 지정은 수락 전(PENDING)이라 회수 이력 없음
    }

    @Test
    void 재확인_수락은_빠진_항목을_회수하고_추가된_항목을_바로_활성화한다() {
        publish(created(List.of(allergyId, medicationId)));
        publish(accepted(RequestType.GENERAL));
        when(careRequestSharingReader.findById(REQUEST_ID)).thenReturn(Optional.of(requestInfo(RequestType.GENERAL)));

        // 복용약을 빼고 반려동물을 추가 (알레르기는 유지)
        publish(changeConfirmed(30L, List.of(allergyId, petId)));

        assertThat(permissionsOf(ASSIGNMENT_ID))
                .extracting(RequestInformationPermission::getInformationId, RequestInformationPermission::getStatus)
                .containsExactlyInAnyOrder(
                        tuple(allergyId, PermissionStatus.ACTIVE),
                        tuple(medicationId, PermissionStatus.REVOKED),
                        tuple(petId, PermissionStatus.ACTIVE));

        List<InformationAccessLog> logs = accessLogRepository.findAll();
        assertThat(logs)
                .extracting(InformationAccessLog::getInformationId, InformationAccessLog::getEventType, InformationAccessLog::getActorUserId)
                .containsExactlyInAnyOrder(
                        tuple(allergyId, AccessEventType.GRANTED, TARGET_ID),        // 수락
                        tuple(medicationId, AccessEventType.GRANTED, TARGET_ID),     // 수락
                        tuple(medicationId, AccessEventType.AUTO_REVOKED, OWNER_ID), // 정보 주인이 뺌
                        tuple(petId, AccessEventType.GRANTED, TARGET_ID));           // 재확인 수락으로 추가
    }

    @Test
    void 회수됐던_항목을_다시_넣으면_기존_행은_두고_새_행을_만든다() {
        publish(created(List.of(allergyId, medicationId)));
        publish(accepted(RequestType.GENERAL));
        when(careRequestSharingReader.findById(REQUEST_ID)).thenReturn(Optional.of(requestInfo(RequestType.GENERAL)));
        publish(changeConfirmed(30L, List.of(allergyId)));                // 복용약 제외
        publish(changeConfirmed(31L, List.of(allergyId, medicationId)));  // 복용약 다시 추가

        assertThat(permissionsOf(ASSIGNMENT_ID))
                .filteredOn(permission -> permission.getInformationId().equals(medicationId))
                .extracting(RequestInformationPermission::getStatus)
                .containsExactlyInAnyOrder(PermissionStatus.REVOKED, PermissionStatus.ACTIVE);
    }

    @Test
    void 권한_처리가_실패하면_같은_트랜잭션의_변경도_함께_롤백된다() {
        long itemsBefore = safetyInformationRepository.count();

        // C 변경 대신 같은 트랜잭션에서 항목 하나를 저장한 뒤, 없는 항목 ID로 이벤트 발행 → D가 실패
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            safetyInformationRepository.save(SafetyInformation.create(OWNER_ID, InformationType.CAUTION, "주의", "내용"));
            eventPublisher.publishEvent(created(List.of(allergyId, 999_999L)));
        })).isInstanceOf(BusinessException.class);

        assertThat(safetyInformationRepository.count()).isEqualTo(itemsBefore); // 앞서 저장한 것도 되돌려짐
        assertThat(permissionRepository.findAll()).isEmpty();
    }

    @Test
    void 트랜잭션_없이_발행하면_권한을_만들지_않고_실패한다() {
        // C는 요청 행을 잠근 트랜잭션 안에서 발행해야 함 (권한만 따로 저장되는 것을 막음)
        assertThatThrownBy(() -> eventPublisher.publishEvent(created(List.of(allergyId))))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(permissionRepository.findAll()).isEmpty();
    }

    // === 도우미 ===

    /** C처럼 트랜잭션 안에서 이벤트를 발행하고 커밋합니다. */
    private void publish(Object event) {
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));
    }

    private Long saveItem(InformationType type) {
        return safetyInformationRepository.save(SafetyInformation.create(OWNER_ID, type, "이름", "내용")).getId();
    }

    private List<RequestInformationPermission> permissionsOf(Long assignmentId) {
        return permissionRepository.findAll().stream()
                .filter(permission -> permission.getAssignmentId().equals(assignmentId))
                .toList();
    }

    private static CareRequestCreatedEvent created(List<Long> informationIds) {
        return new CareRequestCreatedEvent(REQUEST_ID, OWNER_ID, RequestType.GENERAL,
                ASSIGNMENT_ID, TARGET_ID, informationIds, CREATED_AT);
    }

    private static CareRequestAcceptedEvent accepted(RequestType requestType) {
        return new CareRequestAcceptedEvent(REQUEST_ID, OWNER_ID, requestType, ASSIGNMENT_ID, TARGET_ID, ACCEPTED_AT);
    }

    private static CareRequestChangeConfirmedEvent changeConfirmed(Long proposalId, List<Long> informationIds) {
        return new CareRequestChangeConfirmedEvent(REQUEST_ID, OWNER_ID, proposalId, ASSIGNMENT_ID, TARGET_ID,
                Set.of(ChangeableField.INFORMATION_IDS), informationIds, CHANGED_AT);
    }

    private static SharingRequestInfo requestInfo(RequestType requestType) {
        return new SharingRequestInfo(REQUEST_ID, OWNER_ID, ASSIGNMENT_ID, CareRequestStatus.ACCEPTED, requestType,
                null, null, null, TARGET_ID, ACCEPTED_AT, HelpType.HOSPITAL);
    }
}
