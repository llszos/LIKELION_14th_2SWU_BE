package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.carerequest.dto.request.UpdateCareRequestRequest;
import com.gyeot.domain.carerequest.dto.response.UpdateCareRequestResponse.Result;
import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.entity.RequestChangeProposal;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import com.gyeot.domain.carerequest.enums.ChangeableField;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.ProposalStatus;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeProposalRepository;
import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
import com.gyeot.domain.safetycard.query.SafetyInformationSharingReader;
import com.gyeot.domain.situation.query.SituationParticipantReader;
import com.gyeot.global.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.BeforeTransaction;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * "요청당 대기 수정안 1건" 제약(운영 DDL의 생성 컬럼 + UNIQUE)을 테스트 DB에 만들고 요청 수정이 맞게 동작하는지 확인합니다. (#73)
 *
 * - 먼저 제약이 실제로 대기 수정안 두 건을 막는지 확인합니다. (이 테스트가 의미 있으려면 제약이 진짜로 동작해야 함)
 * - 그 상태에서 재수정(기존 수정안 무효 → saveAndFlush → 새 수정안 저장)이 제약에 걸리지 않는지 확인합니다.
 *   Hibernate는 INSERT를 UPDATE보다 먼저 실행해서, 무효 처리를 먼저 DB에 반영하지 않으면 이 제약에 걸립니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAuditingConfig.class,
        SafetyInformationCipher.class,
        CareRequestFinder.class,
        RequestScheduleValidator.class,
        CareRequestEditService.class
})
@ActiveProfiles("test")
@Testcontainers
public class PendingProposalConstraintTest {
    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 9, 0);
    static final LocalDateTime STARTS_AT = NOW.plusDays(2);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            ZoneId seoul = ZoneId.of("Asia/Seoul");
            return Clock.fixed(NOW.atZone(seoul).toInstant(), seoul);
        }
    }

    private static final Long REQUESTER_ID = 10L;
    private static final Long ASSIGNEE_ID = 20L;

    @Autowired private CareRequestEditService editService;
    @Autowired private CareRequestRepository careRequestRepository;
    @Autowired private RequestAssignmentRepository assignmentRepository;
    @Autowired private RequestChangeProposalRepository proposalRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private SafetyInformationSharingReader sharingReader;
    @MockitoBean private SituationParticipantReader situationReader;

    /**
     * 테스트 트랜잭션이 시작되기 전에 제약을 만듭니다.
     * MySQL은 DDL을 실행하면 진행 중인 트랜잭션을 자동 커밋하므로, 테스트 트랜잭션 안에서 실행하면 롤백이 깨집니다.
     */
    @BeforeTransaction
    void createConstraint() {
        PendingProposalConstraint.createIfAbsent(jdbcTemplate);
        when(sharingReader.findSharingInformationIds(any())).thenReturn(List.of());
    }

    @Test
    void 제약이_있으면_같은_요청에_대기_수정안을_두_건_저장할_수_없다() {
        CareRequest request = acceptedRequest();
        proposalRepository.saveAndFlush(proposal(request, "△△병원"));

        assertThatThrownBy(() -> proposalRepository.saveAndFlush(proposal(request, "□□병원")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 재수정은_제약에_걸리지_않고_대기_수정안은_한_건만_남는다() {
        CareRequest request = acceptedRequest();

        editService.update(request.getId(), REQUESTER_ID, placeOnly("△△병원"));
        editService.update(request.getId(), REQUESTER_ID, placeOnly("□□병원"));
        editService.update(request.getId(), REQUESTER_ID, placeOnly("◇◇병원"));
        entityManager.flush();

        assertThat(pendingProposals(request)).singleElement()
                .extracting(RequestChangeProposal::getProposedPlace).isEqualTo("◇◇병원");
    }

    @Test
    void 원래_값으로_되돌려_철회한_뒤_다시_수정해도_제약에_걸리지_않는다() {
        CareRequest request = acceptedRequest();

        editService.update(request.getId(), REQUESTER_ID, placeOnly("△△병원"));
        Result withdrawn = editService.update(request.getId(), REQUESTER_ID, placeOnly("○○병원")).result();
        editService.update(request.getId(), REQUESTER_ID, placeOnly("□□병원"));
        entityManager.flush();

        assertThat(withdrawn).isEqualTo(Result.PROPOSAL_WITHDRAWN);
        assertThat(pendingProposals(request)).hasSize(1);
    }

    // === 준비 코드 ===

    private CareRequest acceptedRequest() {
        CareRequest request = careRequestRepository.save(CareRequest.createGeneral(1L, REQUESTER_ID,
                HelpType.HOSPITAL, "병원 동행 부탁드려요", "○○병원", STARTS_AT, STARTS_AT.plusHours(3), null));
        RequestAssignment assignment = assignmentRepository.save(
                RequestAssignment.assign(request, ASSIGNEE_ID, 1, STARTS_AT.minusHours(1)));
        assignment.respond(AssignmentResponseStatus.AVAILABLE, NOW.minusHours(2));
        request.accept();
        return request;
    }

    private RequestChangeProposal proposal(CareRequest request, String place) {
        RequestAssignment assignment = assignmentRepository
                .findByCareRequestIdAndTargetUserId(request.getId(), ASSIGNEE_ID).orElseThrow();
        return RequestChangeProposal.create(request, assignment, Set.of(ChangeableField.PLACE),
                null, null, place, null, NOW);
    }

    private UpdateCareRequestRequest placeOnly(String place) {
        return new UpdateCareRequestRequest(null, null, place, null);
    }

    private List<RequestChangeProposal> pendingProposals(CareRequest request) {
        return proposalRepository.findAll().stream()
                .filter(proposal -> proposal.getCareRequest().getId().equals(request.getId()))
                .filter(proposal -> proposal.getStatus() == ProposalStatus.PENDING)
                .toList();
    }
}