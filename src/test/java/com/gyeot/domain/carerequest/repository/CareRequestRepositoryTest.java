package com.gyeot.domain.carerequest.repository;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.ChecklistItem;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.entity.RequestChangeEvent;
import com.gyeot.domain.carerequest.entity.RequestChangeProposal;
import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.ChangeableField;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.ProposalStatus;
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
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 MySQL에 저장했다가 다시 읽어 확인하는 작업입니다.
 * - JSON 컬럼(checklist, changed_fields, proposed_information_ids, event_details)이 제대로 저장, 복원되는지 확인합니다.
 * - 자주 쓰는 조회 메서드(열린 지정, 최근 지정, 대기 중 수정안, 지정 순서)가 맞게 동작하는지 확인합니다.
 *
 * @DataJpaTest는 JPA 관련 Bean만 띄우는 가벼운 테스트입니다. (Controller·Security·Service는 뜨지 않음)
 * 각 테스트는 끝나면 자동으로 롤백되어 서로 영향을 주지 않습니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // 내장 DB로 바꾸지 않고 아래 MySQL 사용
@Import({JpaAuditingConfig.class, SafetyInformationCipher.class}) // created_at·updated_at 자동 입력 (없으면 NOT NULL 오류), 안심카드 엔티티의 암호화
@ActiveProfiles("test")
@Testcontainers
class CareRequestRepositoryTest {
    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    private static final Long REQUESTER_ID = 10L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 9, 0);

    @Autowired private CareRequestRepository careRequestRepository;
    @Autowired private RequestAssignmentRepository assignmentRepository;
    @Autowired private RequestChangeProposalRepository proposalRepository;
    @Autowired
    private RequestChangeEventRepository eventRepository;
    @Autowired private EntityManager entityManager;

    @Test
    void 체크리스트_JSON을_저장하고_다시_읽는다() {
        CareRequest request = careRequestRepository.save(newRequest(
                List.of(new ChecklistItem("진료 카드 챙기기"), new ChecklistItem("약국 들르기"))));
        clearPersistenceContext();

        CareRequest found = careRequestRepository.findById(request.getId()).orElseThrow();

        assertThat(found.getChecklist()).containsExactly(
                new ChecklistItem("진료 카드 챙기기"), new ChecklistItem("약국 들르기"));
    }

    @Test
    void 수정안의_JSON_컬럼과_대기_중_조회() {
        CareRequest request = careRequestRepository.save(newRequest(null));
        RequestAssignment assignment = assignmentRepository.save(
                RequestAssignment.assign(request, 20L, 1, NOW.plusHours(24)));
        assignment.respond(AssignmentResponseStatus.AVAILABLE, NOW); // 수락은 지정 → 요청 순서로 바꿈
        request.accept();

        RequestChangeProposal proposal = proposalRepository.save(RequestChangeProposal.create(
                request, assignment, Set.of(ChangeableField.PLACE, ChangeableField.INFORMATION_IDS),
                null, null, "△△병원", List.of(3L, 5L), NOW));
        clearPersistenceContext();

        RequestChangeProposal pending = proposalRepository
                .findByCareRequestIdAndStatus(request.getId(), ProposalStatus.PENDING).orElseThrow();

        assertThat(pending.getId()).isEqualTo(proposal.getId());
        assertThat(pending.getChangedFields())
                .containsExactlyInAnyOrder(ChangeableField.PLACE, ChangeableField.INFORMATION_IDS);
        assertThat(pending.getProposedInformationIds()).containsExactly(3L, 5L);
        assertThat(proposalRepository.findFirstByCareRequestIdOrderByIdDesc(request.getId()))
                .get().extracting(RequestChangeProposal::getId).isEqualTo(proposal.getId());
    }

    @Test
    void changed_fields는_컬럼_이름으로_저장된다() {
        CareRequest request = careRequestRepository.save(newRequest(null));
        RequestAssignment assignment = assignmentRepository.save(
                RequestAssignment.assign(request, 20L, 1, NOW.plusHours(24)));
        assignment.respond(AssignmentResponseStatus.AVAILABLE, NOW);
        request.accept();
        RequestChangeProposal proposal = proposalRepository.save(RequestChangeProposal.create(
                request, assignment, Set.of(ChangeableField.PLACE), null, null, "△△병원", null, NOW));
        entityManager.flush();

        // DB에 실제로 들어간 문자열을 직접 확인 (ChangeableField의 @JsonValue)
        Object raw = entityManager.createNativeQuery(
                        "select changed_fields from request_change_proposals where id = ?1")
                .setParameter(1, proposal.getId())
                .getSingleResult();

        assertThat(raw.toString()).contains("\"place\"").doesNotContain("PLACE");
    }

    @Test
    void 이력의_event_details_JSON을_저장하고_다시_읽는다() {
        CareRequest request = careRequestRepository.save(newRequest(null));
        CareRequestStatus previous = request.getStatus();
        request.expire(NOW);
        eventRepository.save(RequestChangeEvent.statusChanged(request, previous, null, NOW));
        clearPersistenceContext();

        List<RequestChangeEvent> events = eventRepository.findAllByCareRequestIdOrderByOccurredAtAscIdAsc(request.getId());

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventDetails())
                .isEqualTo(Map.of("previous_status", "REQUESTED", "new_status", "EXPIRED"));
        assertThat(events.get(0).isSystemEvent()).isTrue();
    }

    @Test
    void 열린_지정_최근_지정_지정_순서를_조회한다() {
        CareRequest request = careRequestRepository.save(newRequest(null));
        RequestAssignment first = RequestAssignment.assign(request, 20L, 1, NOW.plusHours(24));
        first.respond(AssignmentResponseStatus.UNAVAILABLE, NOW);
        first.close(AssignmentCloseReason.TARGET_CHANGED, NOW);
        assignmentRepository.save(first);
        RequestAssignment second = assignmentRepository.save(
                RequestAssignment.assign(request, 30L, 2, NOW.plusHours(24)));
        clearPersistenceContext();

        Long requestId = request.getId();
        assertThat(assignmentRepository.findByCareRequestIdAndClosedAtIsNull(requestId))
                .get().extracting(RequestAssignment::getId).isEqualTo(second.getId());
        assertThat(assignmentRepository.findFirstByCareRequestIdOrderByAssignmentOrderDesc(requestId))
                .get().extracting(RequestAssignment::getId).isEqualTo(second.getId());
        assertThat(assignmentRepository.findAllByCareRequestIdOrderByAssignmentOrderAsc(requestId))
                .extracting(RequestAssignment::getTargetUserId).containsExactly(20L, 30L);
        assertThat(assignmentRepository.findByCareRequestIdAndTargetUserId(requestId, 20L)).isPresent();
        assertThat(assignmentRepository.findByCareRequestIdAndTargetUserId(requestId, 99L)).isEmpty();
    }

    private CareRequest newRequest(List<ChecklistItem> checklist) {
        return CareRequest.createGeneral(1L, REQUESTER_ID, HelpType.HOSPITAL, "병원 동행 부탁드려요", "○○병원",
                NOW.plusDays(2), NOW.plusDays(2).plusHours(3), checklist);
    }

    /**
     * 지금까지 바꾼 내용을 DB에 반영(flush)하고, JPA가 기억하던 엔티티를 비웁니다(clear).
     */
    private void clearPersistenceContext() {
        entityManager.flush();
        entityManager.clear();
    }
}