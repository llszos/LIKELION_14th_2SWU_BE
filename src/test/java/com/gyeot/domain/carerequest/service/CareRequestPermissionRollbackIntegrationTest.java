package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.access.entity.PermissionStatus;
import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.access.repository.InformationAccessLogRepository;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.access.service.PermissionGrantService;
import com.gyeot.domain.access.service.PermissionRevokeService;
import com.gyeot.domain.carerequest.dto.request.CreateCareRequestRequest;
import com.gyeot.domain.carerequest.dto.request.ReassignRequest;
import com.gyeot.domain.carerequest.dto.request.UpdateCareRequestRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.entity.RequestChangeProposal;
import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.ProposalStatus;
import com.gyeot.domain.carerequest.exception.CareRequestErrorCode;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeEventRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeProposalRepository;
import com.gyeot.domain.relation.entity.Relationship;
import com.gyeot.domain.relation.repository.RelationshipRepository;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import com.gyeot.domain.safetycard.repository.SafetyInformationRepository;
import com.gyeot.domain.situation.query.SituationParticipantReader;
import com.gyeot.domain.situation.query.SituationParticipantReader.SituationSummary;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import com.gyeot.global.exception.ErrorCode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * C 동작 → D 권한 처리가 한 트랜잭션으로 묶여, D가 실패하면 C 변경까지 모두 되돌아가는지 확인합니다. (QA-INT-034, 037, 041)
 *
 * - D의 리스너 테스트(CareRequestPermissionListenerIntegrationTest 등)는 이벤트를 직접 발행합니다.
 *   이 테스트는 C 서비스를 실제로 호출해, C 상태 변경 → 이벤트 → D 처리 실패 → 전체 롤백을 끝까지 확인합니다.
 * - D 실패는 D 서비스(PermissionGrantService, PermissionRevokeService)의 해당 메서드만 예외를 던지게 해서 만듭니다.
 *   D 서비스는 C 트랜잭션 안에서 실행되므로, D가 던진 예외로 C 트랜잭션 전체가 롤백되는지가 확인 대상입니다.
 * - 롤백을 확인한 뒤 실패 설정을 지우고 다시 실행해, 한 번만 반영되고 중복 이력·권한이 생기지 않는지도 확인합니다.
 * - 각 서비스가 실제로 커밋해야 해서 테스트 트랜잭션 없이(@SpringBootTest 기본) 실행하고, 끝나면 데이터를 지웁니다.
 * - B(상황·참여자)는 목으로, A 관계와 D 안심카드·권한은 실제 행으로 준비합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class CareRequestPermissionRollbackIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    private static final Long SITUATION_ID = 1L;
    private static final Long REQUESTER_ID = 10L; // 요청자 = 정보 주인
    private static final Long MINJAE = 20L;
    private static final Long SEOYEON = 30L;

    @Autowired private CareRequestCreateService createService;
    @Autowired private CareRequestResponseService responseService;
    @Autowired private CareRequestReassignService reassignService;
    @Autowired private CareRequestEditService editService;
    @Autowired private ChangeProposalService proposalService;

    @Autowired private CareRequestRepository careRequestRepository;
    @Autowired private RequestAssignmentRepository assignmentRepository;
    @Autowired private RequestChangeProposalRepository proposalRepository;
    @Autowired private RequestChangeEventRepository eventRepository;
    @Autowired private RelationshipRepository relationshipRepository;
    @Autowired private SafetyInformationRepository safetyInformationRepository;
    @Autowired private RequestInformationPermissionRepository permissionRepository;
    @Autowired private InformationAccessLogRepository accessLogRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    @MockitoBean private SituationParticipantReader situationReader; // B
    @MockitoSpyBean private PermissionGrantService grantService;     // D: 부여 실패 주입
    @MockitoSpyBean private PermissionRevokeService revokeService;   // D: 회수 실패 주입

    private Long allergyId;    // X
    private Long medicationId; // Y
    private Long diseaseId;    // Z

    @BeforeEach
    void setUp() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        when(situationReader.findSituation(SITUATION_ID)).thenReturn(Optional.of(new SituationSummary(
                REQUESTER_ID, now.minusDays(1), now.plusDays(10), "ACTIVE")));
        when(situationReader.isActiveParticipant(eq(SITUATION_ID), anyLong())).thenReturn(true);

        relationshipRepository.save(Relationship.createMyPerson(REQUESTER_ID, MINJAE, "동생"));
        relationshipRepository.save(Relationship.createMyPerson(REQUESTER_ID, SEOYEON, "친구"));

        allergyId = safetyInformationRepository.save(
                SafetyInformation.create(REQUESTER_ID, InformationType.ALLERGY, "땅콩", "호흡 곤란")).getId();
        medicationId = safetyInformationRepository.save(
                SafetyInformation.create(REQUESTER_ID, InformationType.MEDICATION, "혈압약", "아침 식후")).getId();
        diseaseId = safetyInformationRepository.save(
                SafetyInformation.create(REQUESTER_ID, InformationType.DISEASE, "고혈압", "정기 진료")).getId();
    }

    @AfterEach
    void cleanUp() {
        accessLogRepository.deleteAllInBatch();
        permissionRepository.deleteAllInBatch();
        eventRepository.deleteAllInBatch();
        proposalRepository.deleteAllInBatch();
        assignmentRepository.deleteAllInBatch();
        careRequestRepository.deleteAllInBatch();
        safetyInformationRepository.deleteAllInBatch();
        relationshipRepository.deleteAllInBatch();
    }

    // === QA-INT-034 수락 철회 ===

    @Test
    void 수락_철회_중_D_권한_회수가_실패하면_철회_전체가_롤백되고_다시_하면_한_번만_반영된다() {
        Long requestId = createRequest(MINJAE, List.of(allergyId, medicationId));
        responseService.respond(requestId, MINJAE, AssignmentResponseStatus.AVAILABLE); // 권한 ACTIVE
        Snapshot before = snapshot(requestId);
        assertThat(permissionsOf(requestId)).extracting(RequestInformationPermission::getStatus)
                .containsOnly(PermissionStatus.ACTIVE);

        // D 회수 실패 → 예외가 C 호출자에게 전파
        failInD(() -> doThrow(new IllegalStateException("D 회수 실패")).when(revokeService).revokeOnAcceptanceWithdrawn(any()));
        assertThatThrownBy(() -> responseService.withdraw(requestId, MINJAE))
                .isInstanceOf(IllegalStateException.class).hasMessage("D 회수 실패");

        // C·D 모두 철회 전 그대로
        assertThat(careRequestRepository.findById(requestId).orElseThrow().getStatus()).isEqualTo(CareRequestStatus.ACCEPTED);
        RequestAssignment assignment = onlyAssignment(requestId);
        assertThat(assignment.isCurrentAssignee()).isTrue();
        assertThat(assignment.getCloseReason()).isNull();
        assertThat(snapshot(requestId)).isEqualTo(before);
        assertThat(permissionsOf(requestId)).extracting(RequestInformationPermission::getStatus,
                RequestInformationPermission::getRevokeReason).containsOnly(tuple(PermissionStatus.ACTIVE, null));

        // 실패 설정을 지우고 다시 철회 → 정상 반영
        reset(revokeService);
        responseService.withdraw(requestId, MINJAE);

        assertThat(careRequestRepository.findById(requestId).orElseThrow().getStatus()).isEqualTo(CareRequestStatus.REQUESTED);
        assertThat(onlyAssignment(requestId).getCloseReason()).isEqualTo(AssignmentCloseReason.ACCEPTANCE_WITHDRAWN);
        assertThat(permissionsOf(requestId)).extracting(RequestInformationPermission::getStatus,
                        RequestInformationPermission::getRevokeReason)
                .containsOnly(tuple(PermissionStatus.REVOKED, RevokeReason.ASSIGNMENT_CLOSED));
        Snapshot afterRetry = snapshot(requestId);
        assertThat(afterRetry.accessLogs()).isEqualTo(before.accessLogs() + 2); // ACTIVE였던 항목마다 회수 이력 1건

        // 한 번 더 누르면 이미 담당자가 아니라 403, 아무것도 늘지 않음
        assertError(() -> responseService.withdraw(requestId, MINJAE), CommonErrorCode.FORBIDDEN);
        assertThat(snapshot(requestId)).isEqualTo(afterRetry);
    }

    // === QA-INT-037 다시 부탁하기 ===

    @Test
    void 다시_부탁하기_중_D_권한_처리가_실패하면_새_지정과_이전_지정_마감이_모두_롤백되고_다시_하면_한_번만_반영된다() {
        Long requestId = createRequest(MINJAE, List.of(allergyId));
        responseService.respond(requestId, MINJAE, AssignmentResponseStatus.UNAVAILABLE); // 담당자 없음
        Snapshot before = snapshot(requestId);
        Long firstAssignmentId = onlyAssignment(requestId).getId();

        failInD(() -> doThrow(new IllegalStateException("D 부여 실패")).when(grantService).grantOnTargetChanged(any()));
        assertThatThrownBy(() -> reassignService.reassign(requestId, REQUESTER_ID, reassignTo(SEOYEON)))
                .isInstanceOf(IllegalStateException.class).hasMessage("D 부여 실패");

        // 새 지정 없음, 이전 지정은 열린 채로, 권한은 이전 대상의 PENDING 그대로
        RequestAssignment first = onlyAssignment(requestId);
        assertThat(first.getId()).isEqualTo(firstAssignmentId);
        assertThat(first.isOpen()).isTrue();
        assertThat(snapshot(requestId)).isEqualTo(before);
        assertThat(permissionsOf(requestId)).extracting(RequestInformationPermission::getAssignmentId,
                        RequestInformationPermission::getStatus)
                .containsOnly(tuple(firstAssignmentId, PermissionStatus.PENDING));

        reset(grantService);
        reassignService.reassign(requestId, REQUESTER_ID, reassignTo(SEOYEON));

        List<RequestAssignment> assignments = assignmentRepository.findAllByCareRequestIdOrderByAssignmentOrderAsc(requestId);
        assertThat(assignments).extracting(RequestAssignment::getAssignmentOrder).containsExactly(1, 2);
        assertThat(assignments.get(0).getCloseReason()).isEqualTo(AssignmentCloseReason.TARGET_CHANGED);
        assertThat(assignments.get(1).getTargetUserId()).isEqualTo(SEOYEON);
        Long secondAssignmentId = assignments.get(1).getId();
        assertThat(permissionsOf(requestId)).extracting(RequestInformationPermission::getAssignmentId,
                        RequestInformationPermission::getStatus, RequestInformationPermission::getRevokeReason)
                .containsExactlyInAnyOrder(
                        tuple(firstAssignmentId, PermissionStatus.REVOKED, RevokeReason.ASSIGNMENT_CLOSED),
                        tuple(secondAssignmentId, PermissionStatus.PENDING, null));
        Snapshot afterRetry = snapshot(requestId);

        // 새 대상이 응답 대기라 다시 부탁할 수 없음, 지정·권한이 늘지 않음
        assertError(() -> reassignService.reassign(requestId, REQUESTER_ID, reassignTo(SEOYEON)),
                CareRequestErrorCode.REQUEST_NOT_REASSIGNABLE);
        assertThat(snapshot(requestId)).isEqualTo(afterRetry);
    }

    // === QA-INT-041 재확인 수락 ===

    @Test
    void 재확인_수락_중_D_권한_처리가_실패하면_요청_내용과_수정안과_권한이_모두_롤백되고_다시_하면_한_번만_반영된다() {
        Long requestId = createRequest(MINJAE, List.of(allergyId, medicationId));
        responseService.respond(requestId, MINJAE, AssignmentResponseStatus.AVAILABLE); // X, Y ACTIVE
        Long proposalId = editService.update(requestId, REQUESTER_ID,
                new UpdateCareRequestRequest(null, null, "△△병원", List.of(medicationId, diseaseId))).proposalId();
        assertThat(proposalId).isNotNull(); // 수락 후 수정 → 재확인 대기
        Snapshot before = snapshot(requestId);

        failInD(() -> doThrow(new IllegalStateException("D 반영 실패")).when(grantService).grantOnChangeConfirmed(any()));
        assertThatThrownBy(() -> proposalService.accept(requestId, proposalId, MINJAE))
                .isInstanceOf(IllegalStateException.class).hasMessage("D 반영 실패");

        // 요청 장소는 수정 전, 수정안은 대기 중, 권한은 X, Y ACTIVE 그대로 (Z 없음)
        assertThat(careRequestRepository.findById(requestId).orElseThrow().getPlace()).isEqualTo("○○병원");
        assertThat(proposalRepository.findById(proposalId).orElseThrow().getStatus()).isEqualTo(ProposalStatus.PENDING);
        assertThat(snapshot(requestId)).isEqualTo(before);
        assertThat(permissionsOf(requestId)).extracting(RequestInformationPermission::getInformationId,
                        RequestInformationPermission::getStatus)
                .containsExactlyInAnyOrder(tuple(allergyId, PermissionStatus.ACTIVE), tuple(medicationId, PermissionStatus.ACTIVE));

        reset(grantService);
        proposalService.accept(requestId, proposalId, MINJAE);

        assertThat(careRequestRepository.findById(requestId).orElseThrow().getPlace()).isEqualTo("△△병원");
        RequestChangeProposal accepted = proposalRepository.findById(proposalId).orElseThrow();
        assertThat(accepted.getStatus()).isEqualTo(ProposalStatus.ACCEPTED);
        // X는 제외로 회수, Y는 기존 행 유지, Z는 새로 ACTIVE
        assertThat(permissionsOf(requestId)).extracting(RequestInformationPermission::getInformationId,
                        RequestInformationPermission::getStatus, RequestInformationPermission::getRevokeReason)
                .containsExactlyInAnyOrder(
                        tuple(allergyId, PermissionStatus.REVOKED, RevokeReason.SHARING_EXCLUDED),
                        tuple(medicationId, PermissionStatus.ACTIVE, null),
                        tuple(diseaseId, PermissionStatus.ACTIVE, null));
        Snapshot afterRetry = snapshot(requestId);

        // 같은 수정안을 다시 수락하면 409, 권한·이력이 늘지 않음
        assertError(() -> proposalService.accept(requestId, proposalId, MINJAE),
                CareRequestErrorCode.REQUEST_PROPOSAL_NOT_PENDING);
        assertThat(snapshot(requestId)).isEqualTo(afterRetry);
    }

    // === 도우미 ===

    /**
     * D 서비스는 호출하는 트랜잭션에 참여해야만(MANDATORY) 실행되는 프록시라,
     * Mockito의 stub 설정 호출도 트랜잭션 안에서 해야 프록시를 지나 spy에 기록됩니다.
     */
    private void failInD(Runnable stubbing) {
        transactionTemplate.executeWithoutResult(status -> stubbing.run());
    }

    /** 요청자가 상황 안에서 targetUserId에게 요청을 보냅니다. (시작 3일 뒤, 3시간) */
    private Long createRequest(Long targetUserId, List<Long> informationIds) {
        LocalDateTime startsAt = LocalDateTime.now(ZoneId.of("Asia/Seoul")).plusDays(3).truncatedTo(ChronoUnit.MINUTES);
        return createService.create(REQUESTER_ID, new CreateCareRequestRequest(SITUATION_ID, HelpType.HOSPITAL,
                startsAt, startsAt.plusHours(3), "○○병원", null, targetUserId, 24, informationIds)).requestId();
    }

    private ReassignRequest reassignTo(Long targetUserId) {
        return new ReassignRequest(targetUserId, 24, List.of(allergyId));
    }

    private RequestAssignment onlyAssignment(Long requestId) {
        List<RequestAssignment> assignments = assignmentRepository.findAllByCareRequestIdOrderByAssignmentOrderAsc(requestId);
        assertThat(assignments).hasSize(1);
        return assignments.get(0);
    }

    private List<RequestInformationPermission> permissionsOf(Long requestId) {
        return permissionRepository.findAll().stream()
                .filter(p -> p.getRequestId().equals(requestId))
                .toList();
    }

    /** 롤백·중복 확인용 행 수 (한 테스트에 요청이 하나뿐이라 전체 수로 봄) */
    private Snapshot snapshot(Long requestId) {
        return new Snapshot(assignmentRepository.findAllByCareRequestIdOrderByAssignmentOrderAsc(requestId).size(),
                eventRepository.count(), proposalRepository.count(), permissionsOf(requestId).size(),
                accessLogRepository.count());
    }

    private record Snapshot(int assignments, long events, long proposals, int permissions, long accessLogs) {
    }

    private void assertError(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }
}
