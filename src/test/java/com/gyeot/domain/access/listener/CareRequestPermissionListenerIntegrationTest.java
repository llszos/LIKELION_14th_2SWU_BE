package com.gyeot.domain.access.listener;

import com.gyeot.domain.access.entity.AccessEventType;
import com.gyeot.domain.access.entity.InformationAccessLog;
import com.gyeot.domain.access.entity.PermissionStatus;
import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.access.repository.InformationAccessLogRepository;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;
import com.gyeot.domain.carerequest.enums.CancelReason;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.ChangeableField;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.RequestType;
import com.gyeot.domain.carerequest.event.CareRequestAcceptanceWithdrawnEvent;
import com.gyeot.domain.carerequest.event.CareRequestAcceptedEvent;
import com.gyeot.domain.carerequest.event.CareRequestAssignmentClosedEvent;
import com.gyeot.domain.carerequest.event.CareRequestCanceledEvent;
import com.gyeot.domain.carerequest.event.CareRequestChangeConfirmedEvent;
import com.gyeot.domain.carerequest.event.CareRequestCompletedEvent;
import com.gyeot.domain.carerequest.event.CareRequestCreatedEvent;
import com.gyeot.domain.carerequest.event.CareRequestExpiredEvent;
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
 * C 요청 이벤트 → 리스너 → 권한 서비스 → DB까지 실제로 이어지는지 확인합니다. (#42 부여, #43 회수)
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
    private static final LocalDateTime ENDED_AT = CREATED_AT.plusHours(3);   // 완료, 취소, 만료, 철회, 마감 시각

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

    @Test
    void 재확인_대기_중_삭제된_항목은_빼고_남은_항목으로_반영한다() {

        // ERD 4.8: 담당자는 항목을 고칠 수 없으므로, 대기 중 삭제된 항목 때문에 재확인 수락이 막히면 안 됨
        publish(created(List.of(allergyId)));
        publish(accepted(RequestType.GENERAL));

        when(careRequestSharingReader.findById(REQUEST_ID)).thenReturn(Optional.of(requestInfo(RequestType.GENERAL)));

        safetyInformationRepository.deleteById(petId); // 복용약, 반려동물 추가를 제안한 뒤 반려동물 삭제

        publish(changeConfirmed(30L, List.of(allergyId, medicationId, petId)));

        assertThat(permissionsOf(ASSIGNMENT_ID))
                .extracting(RequestInformationPermission::getInformationId, RequestInformationPermission::getStatus)
                .containsExactlyInAnyOrder(tuple(allergyId, PermissionStatus.ACTIVE), tuple(medicationId, PermissionStatus.ACTIVE));
    }

    @Test
    void 재확인_목록이_전부_삭제됐으면_기존_권한을_회수하고_새로_만들지_않는다() {

        publish(created(List.of(allergyId)));
        publish(accepted(RequestType.GENERAL));

        // 유지할 알레르기와 추가할 복용약이 대기 중 모두 삭제된 상황입니다.
        // 삭제 API의 권한 정리는 제외하고, 재확인 이벤트의 빈 반영 목록 처리를 검증합니다.
        safetyInformationRepository.deleteById(allergyId);
        safetyInformationRepository.deleteById(medicationId);

        publish(changeConfirmed(30L, List.of(allergyId, medicationId)));

        assertThat(permissionsOf(ASSIGNMENT_ID)).singleElement().satisfies(permission -> {
            assertThat(permission.getInformationId()).isEqualTo(allergyId);
            assertThat(permission.getStatus()).isEqualTo(PermissionStatus.REVOKED);
            assertThat(permission.getRevokedAt()).isEqualTo(CHANGED_AT);
        });

        assertThat(accessLogRepository.findAll())
                .extracting(InformationAccessLog::getInformationId, InformationAccessLog::getEventType)
                .containsExactlyInAnyOrder(
                        tuple(allergyId, AccessEventType.GRANTED),
                        tuple(allergyId, AccessEventType.AUTO_REVOKED));
    }

    @Test
    void 유지_항목이_중복된_전체_목록은_회수_전에_거부한다() {
        publish(created(List.of(allergyId, medicationId)));

        // 알레르기가 두 번 들어온 목록: 추가 항목은 없지만 전체 목록 검증에서 거부
        assertThatThrownBy(() -> publish(changeConfirmed(null, List.of(allergyId, allergyId))))
                .isInstanceOf(BusinessException.class);

        assertThat(permissionsOf(ASSIGNMENT_ID)).hasSize(2)
                .allSatisfy(p -> assertThat(p.getStatus()).isEqualTo(PermissionStatus.PENDING));
        assertThat(accessLogRepository.findAll()).isEmpty();
    }

    @Test
    void 수락_전_수정에서_유지_항목이_삭제됐으면_추가_항목이_없어도_전체_수정을_거부한다() {
        publish(created(List.of(allergyId, medicationId)));
        safetyInformationRepository.deleteById(allergyId); // 유지하려는 항목이 그 사이 삭제됨

        // 복용약만 빼는 수정: 추가 항목은 없지만 유지 항목(알레르기)이 없어 거부, 복용약 회수도 실행되지 않음
        assertThatThrownBy(() -> publish(changeConfirmed(null, List.of(allergyId))))
                .isInstanceOf(BusinessException.class);

        assertThat(permissionsOf(ASSIGNMENT_ID)).hasSize(2)
                .allSatisfy(p -> assertThat(p.getStatus()).isEqualTo(PermissionStatus.PENDING));
    }

    // === 회수 (#43) ===
    // 요청 자체가 끝나면(완료, 취소, 만료) 요청 기준 REQUEST_ENDED, 지정만 끝나면(수락 철회, 지정 마감) 지정 기준 ASSIGNMENT_CLOSED

    @Test
    void 요청_완료면_권한이_REQUEST_ENDED로_회수되고_ACTIVE였던_항목마다_회수_이력이_남는다() {

        publish(created(List.of(allergyId, medicationId)));
        publish(accepted(RequestType.GENERAL));

        publish(completed());

        assertThat(permissionsOf(ASSIGNMENT_ID)).hasSize(2).allSatisfy(permission -> {
            assertThat(permission.getStatus()).isEqualTo(PermissionStatus.REVOKED);
            assertThat(permission.getRevokeReason()).isEqualTo(RevokeReason.REQUEST_ENDED);
            assertThat(permission.getRevokedAt()).isEqualTo(ENDED_AT);
        });

        // 부여 2건 + 회수 2건, 회수 이력은 시스템 처리라 수행자 없음
        assertThat(logsOf(AccessEventType.AUTO_REVOKED)).hasSize(2).allSatisfy(log -> {
            assertThat(log.getOwnerUserId()).isEqualTo(OWNER_ID);
            assertThat(log.getActorUserId()).isNull();
            assertThat(log.getOccurredAt()).isEqualTo(ENDED_AT);
        });

        assertThat(logsOf(AccessEventType.GRANTED)).hasSize(2);
    }

    @Test
    void 요청_취소면_이전_지정에_남은_권한까지_요청의_권한이_모두_회수된다() {

        // 지정 7번의 권한은 일부러 회수하지 않은 채로 둠 (요청 기준으로 모두 찾는지 확인)
        publish(created(List.of(allergyId)));

        publish(new CareRequestCreatedEvent(REQUEST_ID, OWNER_ID, RequestType.GENERAL,
                NEXT_ASSIGNMENT_ID, NEXT_TARGET_ID, List.of(medicationId), CREATED_AT));

        publish(new CareRequestCanceledEvent(REQUEST_ID, OWNER_ID, CancelReason.REQUESTER_CANCELED,
                NEXT_ASSIGNMENT_ID, NEXT_TARGET_ID, ENDED_AT));

        assertThat(permissionRepository.findAll()).hasSize(2).allSatisfy(permission -> {
            assertThat(permission.getStatus()).isEqualTo(PermissionStatus.REVOKED);
            assertThat(permission.getRevokeReason()).isEqualTo(RevokeReason.REQUEST_ENDED);
        });

        assertThat(accessLogRepository.findAll()).isEmpty(); // 모두 수락 전(PENDING)이라 회수 이력 없음
    }

    @Test
    void 요청_종료는_다른_요청의_권한을_건드리지_않는다() {

        // 같은 정보 주인의 다른 요청(2번, 지정 9번)
        publish(created(List.of(allergyId)));
        publish(new CareRequestCreatedEvent(2L, OWNER_ID, RequestType.GENERAL,
                9L, NEXT_TARGET_ID, List.of(allergyId), CREATED_AT));

        // 요청 1번만 만료
        publish(new CareRequestExpiredEvent(REQUEST_ID, OWNER_ID, ASSIGNMENT_ID, TARGET_ID, ENDED_AT));

        assertThat(permissionsOf(ASSIGNMENT_ID)).singleElement()
                .extracting(RequestInformationPermission::getStatus).isEqualTo(PermissionStatus.REVOKED);

        assertThat(permissionsOf(9L)).singleElement()
                .extracting(RequestInformationPermission::getStatus).isEqualTo(PermissionStatus.PENDING);
    }

    @Test
    void 수락_철회면_그_지정의_권한이_ASSIGNMENT_CLOSED로_회수되고_회수_이력이_남는다() {

        publish(created(List.of(allergyId, medicationId)));
        publish(accepted(RequestType.GENERAL));

        // 담당자가 시작 전에 수락을 철회함 (요청은 계속 열려 있어 다시 부탁하기 가능)
        publish(new CareRequestAcceptanceWithdrawnEvent(REQUEST_ID, OWNER_ID, ASSIGNMENT_ID, TARGET_ID, ENDED_AT));

        assertThat(permissionsOf(ASSIGNMENT_ID)).hasSize(2).allSatisfy(permission -> {
            assertThat(permission.getStatus()).isEqualTo(PermissionStatus.REVOKED);
            assertThat(permission.getRevokeReason()).isEqualTo(RevokeReason.ASSIGNMENT_CLOSED);
            assertThat(permission.getRevokedAt()).isEqualTo(ENDED_AT);
        });

        assertThat(logsOf(AccessEventType.AUTO_REVOKED)).hasSize(2); // 수락 후(ACTIVE)라 항목마다 회수 이력
    }

    @Test
    void 수락_전_지정_마감이면_ASSIGNMENT_CLOSED로_회수되고_이력은_남지_않는다() {

        publish(created(List.of(allergyId, medicationId)));

        publish(new CareRequestAssignmentClosedEvent(REQUEST_ID, OWNER_ID, ASSIGNMENT_ID, TARGET_ID,
                AssignmentCloseReason.PARTICIPANT_LEFT, ENDED_AT));

        assertThat(permissionsOf(ASSIGNMENT_ID)).hasSize(2).allSatisfy(permission -> {
            assertThat(permission.getStatus()).isEqualTo(PermissionStatus.REVOKED);
            assertThat(permission.getRevokeReason()).isEqualTo(RevokeReason.ASSIGNMENT_CLOSED);
        });

        assertThat(accessLogRepository.findAll()).isEmpty(); // 담당자가 본 적 없는 PENDING이라 이력 없음
    }

    @Test
    void 같은_회수_이벤트가_다시_와도_처음_회수한_사유와_시각과_이력이_그대로다() {

        publish(created(List.of(allergyId)));
        publish(accepted(RequestType.GENERAL));
        publish(completed());

        // 같은 완료 이벤트가 늦게 한 번 더 옴
        publish(new CareRequestCompletedEvent(REQUEST_ID, OWNER_ID, ASSIGNMENT_ID, TARGET_ID, TARGET_ID,
                ENDED_AT.plusMinutes(5)));

        // 이미 REVOKED라 조회되지 않아, 두 번째 이벤트 시각으로 덮어쓰지 않고 이력도 늘지 않음
        assertThat(permissionsOf(ASSIGNMENT_ID)).singleElement()
                .extracting(RequestInformationPermission::getRevokedAt).isEqualTo(ENDED_AT);

        assertThat(logsOf(AccessEventType.AUTO_REVOKED)).hasSize(1);
    }

    @Test
    void 수락_철회_후_열린_지정_없이_만료돼도_철회_때_회수한_사유가_그대로다() {

        publish(created(List.of(allergyId)));
        publish(accepted(RequestType.GENERAL));
        publish(new CareRequestAcceptanceWithdrawnEvent(REQUEST_ID, OWNER_ID, ASSIGNMENT_ID, TARGET_ID, CHANGED_AT));

        // 다시 부탁하지 않고 시작 시각이 지나 만료, 열린 지정이 없어 assignmentId가 NULL
        publish(new CareRequestExpiredEvent(REQUEST_ID, OWNER_ID, null, null, ENDED_AT));

        assertThat(permissionsOf(ASSIGNMENT_ID)).singleElement().satisfies(permission -> {
            assertThat(permission.getRevokeReason()).isEqualTo(RevokeReason.ASSIGNMENT_CLOSED);
            assertThat(permission.getRevokedAt()).isEqualTo(CHANGED_AT);
        });

        assertThat(logsOf(AccessEventType.AUTO_REVOKED)).hasSize(1); // 철회 때 1건만
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

    private List<InformationAccessLog> logsOf(AccessEventType eventType) {
        return accessLogRepository.findAll().stream()
                .filter(log -> log.getEventType() == eventType)
                .toList();
    }

    private static CareRequestCompletedEvent completed() {

        // 담당자(20번)가 완료 처리
        return new CareRequestCompletedEvent(REQUEST_ID, OWNER_ID, ASSIGNMENT_ID, TARGET_ID, TARGET_ID, ENDED_AT);
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
