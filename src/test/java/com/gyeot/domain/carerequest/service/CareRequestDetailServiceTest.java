package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.carerequest.dto.response.CareRequestDetailResponse;
import com.gyeot.domain.carerequest.dto.response.CareRequestDetailResponse.AssignmentView;
import com.gyeot.domain.carerequest.dto.response.CareRequestDetailResponse.InformationView;
import com.gyeot.domain.carerequest.dto.response.CareRequestDetailResponse.ProposalView;
import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.entity.RequestChangeEvent;
import com.gyeot.domain.carerequest.entity.RequestChangeProposal;
import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import com.gyeot.domain.carerequest.enums.CancelReason;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.ChangeableField;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.InvalidationReason;
import com.gyeot.domain.carerequest.enums.NoAssigneeReason;
import com.gyeot.domain.carerequest.enums.ProposalStatus;
import com.gyeot.domain.carerequest.exception.CareRequestErrorCode;
import com.gyeot.domain.carerequest.query.RequestDisplayStatus;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeEventRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeProposalRepository;
import com.gyeot.domain.relation.entity.Relationship;
import com.gyeot.domain.relation.repository.RelationshipRepository;
import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
import com.gyeot.domain.safetycard.query.SafetyInformationSharingReader;
import com.gyeot.domain.situation.query.SituationParticipantReader;
import com.gyeot.domain.situation.query.SituationParticipantReader.SituationSummary;
import com.gyeot.domain.user.query.UserProfileQuery;
import com.gyeot.domain.user.query.UserProfileQuery.UserProfile;
import com.gyeot.global.config.JpaAuditingConfig;
import com.gyeot.global.exception.BusinessException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.gyeot.domain.carerequest.service.RequestAction.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 도움 요청 상세 조회(#69)를 실제 MySQL로 확인합니다.
 * A 관계는 실제 관계 행으로, A 이름·B 상황·D 공개 항목은 목으로 대신합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAuditingConfig.class,
        SafetyInformationCipher.class, // 안심카드 엔티티의 암호화 Converter가 필요로 함
        CareRequestFinder.class,
        RequestTargetEligibility.class,
        RequestUserNames.class,
        CareRequestDetailService.class,
        CareRequestDetailServiceTest.FixedClockConfig.class // @Nested 안쪽 클래스는 바깥의 설정을 자동으로 못 찾아서 직접 등록
})
@ActiveProfiles("test")
@Testcontainers
public class CareRequestDetailServiceTest {
    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 9, 0);
    static final LocalDateTime STARTS_AT = NOW.plusDays(2);      // 11/12 09:00
    static final LocalDateTime ENDS_AT = STARTS_AT.plusHours(3); // 11/12 12:00

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            ZoneId seoul = ZoneId.of("Asia/Seoul");
            return Clock.fixed(NOW.atZone(seoul).toInstant(), seoul);
        }
    }

    private static final Long SITUATION_ID = 1L;
    private static final Long REQUESTER_ID = 10L; // 김지우
    private static final Long MINJAE = 20L;       // 서로의 보호자
    private static final Long SEOYEON = 30L;      // 나의 사람
    private static final Long STRANGER_ID = 99L;

    @Autowired private CareRequestDetailService detailService;
    @Autowired private CareRequestRepository careRequestRepository;
    @Autowired private RequestAssignmentRepository assignmentRepository;
    @Autowired private RequestChangeProposalRepository proposalRepository;
    @Autowired private RequestChangeEventRepository eventRepository;
    @Autowired private RelationshipRepository relationshipRepository;
    @Autowired private EntityManager entityManager;

    @MockitoBean private SituationParticipantReader situationReader;
    @MockitoBean private SafetyInformationSharingReader sharingReader;
    @MockitoBean private UserProfileQuery userProfileQuery;

    @BeforeEach
    void setUp() {
        situation("ACTIVE");
        when(userProfileQuery.findProfilesByUserIds(anyCollection())).thenReturn(profiles(
                new UserProfile(REQUESTER_ID, "김지우", false),
                new UserProfile(MINJAE, "박민재", false),
                new UserProfile(SEOYEON, "이서연", false)));
        when(sharingReader.findSharingInformationIds(any())).thenReturn(List.of(15L, 12L));
        when(sharingReader.findInformationNames(eq(REQUESTER_ID), anyCollection())).thenReturn(Map.of(
                12L, "생년월일", 15L, "출입 방법", 20L, "복용약"));

        relationshipRepository.save(Relationship.createGuardian(REQUESTER_ID, MINJAE, 1));
        relationshipRepository.save(Relationship.createGuardian(MINJAE, REQUESTER_ID, 1));
        relationshipRepository.save(Relationship.createMyPerson(REQUESTER_ID, SEOYEON, "친구"));
    }

    // === 요청자 뷰 ===

    @Nested
    class 요청자_뷰 {

        @Test
        void 응답_대기_요청의_기본_정보와_요청자_전용_필드를_돌려준다() {
            CareRequest request = request();
            RequestAssignment assignment = assignmentRepository.save(
                    RequestAssignment.assign(request, MINJAE, 1, NOW.plusHours(24), 24));
            flushAndClear();

            CareRequestDetailResponse detail = detailService.getDetail(request.getId(), REQUESTER_ID);

            assertThat(detail.requestId()).isEqualTo(request.getId());
            assertThat(detail.situation().situationId()).isEqualTo(SITUATION_ID);
            assertThat(detail.situation().startAt()).isEqualTo(LocalDateTime.of(2026, 11, 9, 9, 0));
            assertThat(detail.situation().endAt()).isEqualTo(LocalDateTime.of(2026, 11, 20, 18, 0));
            assertThat(detail.situation().status()).isEqualTo("ACTIVE");
            assertThat(detail.helpType()).isEqualTo(HelpType.HOSPITAL);
            assertThat(detail.description()).isNull(); // 부탁 내용 없이 보낸 요청
            assertThat(detail.place()).isEqualTo("○○병원");
            assertThat(detail.status()).isEqualTo(CareRequestStatus.REQUESTED);
            assertThat(detail.displayStatus()).isEqualTo(RequestDisplayStatus.WAITING);
            assertThat(detail.viewerRole()).isEqualTo(RequestViewerRole.REQUESTER);
            assertThat(detail.requester().name()).isEqualTo("김지우");

            assertThat(detail.currentAssignment().assignmentId()).isEqualTo(assignment.getId());
            assertThat(detail.currentAssignment().target().name()).isEqualTo("박민재");
            assertThat(detail.currentAssignment().target().isGuardian()).isTrue();
            assertThat(detail.currentAssignment().responseDeadline()).isEqualTo(NOW.plusHours(24));
            assertThat(detail.currentAssignment().closed()).isFalse();
            assertThat(detail.noAssigneeReason()).isNull();
            assertThat(detail.assignments()).hasSize(1);
            assertThat(detail.responseDeadlineHours()).isEqualTo(24);
            assertThat(detail.myAssignment()).isNull();
            assertThat(detail.latestProposal()).isNull();
            assertThat(detail.actions()).containsExactly(CANCEL, EDIT);
        }

        @Test
        void 다시_부탁한_요청은_부탁_순서와_가장_최근_지정을_돌려준다() {
            CareRequest request = request();
            RequestAssignment first = assignmentRepository.save(RequestAssignment.assign(request, MINJAE, 1, NOW.plusHours(24)));
            first.respond(AssignmentResponseStatus.UNAVAILABLE, NOW.minusHours(2));
            first.close(AssignmentCloseReason.TARGET_CHANGED, NOW.minusHours(1));
            entityManager.flush();  // 마감을 먼저 반영 (요청당 열린 지정 1건 제약)
            RequestAssignment second = assignmentRepository.save(
                    RequestAssignment.assign(request, SEOYEON, 2, NOW.plusHours(12), 12));
            flushAndClear();

            CareRequestDetailResponse detail = detailService.getDetail(request.getId(), REQUESTER_ID);

            assertThat(detail.assignments()).extracting(AssignmentView::order).containsExactly(1, 2);
            assertThat(detail.assignments().get(0).target().name()).isEqualTo("박민재");
            assertThat(detail.assignments().get(0).responseStatus()).isEqualTo(AssignmentResponseStatus.UNAVAILABLE);
            assertThat(detail.assignments().get(0).closeReason()).isEqualTo(AssignmentCloseReason.TARGET_CHANGED);
            assertThat(detail.assignments().get(1).target().name()).isEqualTo("이서연");
            assertThat(detail.assignments().get(1).target().isGuardian()).isFalse(); // 나의 사람
            assertThat(detail.currentAssignment().assignmentId()).isEqualTo(second.getId());
            assertThat(detail.responseDeadlineHours()).isEqualTo(12); // 마지막 대상에게 고른 값
            assertThat(detail.displayStatus()).isEqualTo(RequestDisplayStatus.WAITING);
        }

        @Test
        void 어려움_응답이면_담당자_없음_사유와_다시_부탁하기를_돌려준다() {
            CareRequest request = request();
            assignmentRepository.save(RequestAssignment.assign(request, MINJAE, 1, NOW.plusHours(24)))
                    .respond(AssignmentResponseStatus.UNAVAILABLE, NOW.minusHours(1));
            flushAndClear();

            CareRequestDetailResponse detail = detailService.getDetail(request.getId(), REQUESTER_ID);

            assertThat(detail.displayStatus()).isEqualTo(RequestDisplayStatus.NO_ASSIGNEE);
            assertThat(detail.noAssigneeReason()).isEqualTo(NoAssigneeReason.UNAVAILABLE);
            assertThat(detail.actions()).containsExactly(CANCEL, EDIT, REASSIGN);
        }

        @Test
        void 응답_기한이_지나면_기한_지남_사유를_돌려준다() {
            CareRequest request = request();
            assignmentRepository.save(RequestAssignment.assign(request, MINJAE, 1, NOW.minusMinutes(1)));
            flushAndClear();

            CareRequestDetailResponse detail = detailService.getDetail(request.getId(), REQUESTER_ID);

            assertThat(detail.displayStatus()).isEqualTo(RequestDisplayStatus.NO_ASSIGNEE);
            assertThat(detail.noAssigneeReason()).isEqualTo(NoAssigneeReason.DEADLINE_PASSED);
            assertThat(detail.actions()).contains(REASSIGN);
        }

        @Test
        void 수락_철회로_지정이_마감되면_현재_지정에_마감_정보를_담는다() {
            CareRequest request = request();
            RequestAssignment assignment = accepted(request, MINJAE);
            assignment.close(AssignmentCloseReason.ACCEPTANCE_WITHDRAWN, NOW.minusMinutes(30));
            request.revertToRequested();
            flushAndClear();

            CareRequestDetailResponse detail = detailService.getDetail(request.getId(), REQUESTER_ID);

            assertThat(detail.currentAssignment().closed()).isTrue();
            assertThat(detail.currentAssignment().closeReason()).isEqualTo(AssignmentCloseReason.ACCEPTANCE_WITHDRAWN);
            assertThat(detail.currentAssignment().acceptedAt()).isNotNull();
            assertThat(detail.noAssigneeReason()).isEqualTo(NoAssigneeReason.ACCEPTANCE_WITHDRAWN);
            assertThat(detail.actions()).containsExactly(CANCEL, EDIT, REASSIGN);
        }

        @Test
        void 시작_시각이_지난_담당자_없음_요청은_취소만_할_수_있다() {
            CareRequest request = careRequestRepository.save(CareRequest.createGeneral(SITUATION_ID, REQUESTER_ID,
                    HelpType.MEAL, null, "집", NOW.minusMinutes(10), NOW.plusHours(2), null));
            assignmentRepository.save(RequestAssignment.assign(request, MINJAE, 1, NOW.minusMinutes(10)));
            flushAndClear(); // 스케줄러가 만료 처리하기 전

            assertThat(detailService.getDetail(request.getId(), REQUESTER_ID).actions()).containsExactly(CANCEL);
        }

        @Test
        void 탈퇴한_대상자는_탈퇴한_사용자로_보여준다() {
            when(userProfileQuery.findProfilesByUserIds(anyCollection())).thenReturn(profiles(
                    new UserProfile(REQUESTER_ID, "김지우", false),
                    new UserProfile(MINJAE, null, true)));
            CareRequest request = request();
            assignmentRepository.save(RequestAssignment.assign(request, MINJAE, 1, NOW.plusHours(24)));
            flushAndClear();

            assertThat(detailService.getDetail(request.getId(), REQUESTER_ID).currentAssignment().target().name())
                    .isEqualTo(RequestUserNames.WITHDRAWN_USER_NAME);
        }

        @Test
        void 해제된_보호자_관계는_보호자로_표시하지_않는다() {
            CareRequest request = request();
            assignmentRepository.save(RequestAssignment.assign(request, MINJAE, 1, NOW.plusHours(24)));
            relationshipRepository.findAll().stream()
                    .filter(relationship -> relationship.getTargetUserId().equals(MINJAE))
                    .forEach(relationship -> relationship.end(NOW.minusMinutes(5)));
            flushAndClear();

            assertThat(detailService.getDetail(request.getId(), REQUESTER_ID).currentAssignment().target().isGuardian())
                    .isFalse();
        }

        @Test
        void 진행_중이면_완료와_취소를_할_수_있다() {
            CareRequest request = request();
            accepted(request, MINJAE);
            request.start(NOW.minusMinutes(5));
            flushAndClear();

            CareRequestDetailResponse detail = detailService.getDetail(request.getId(), REQUESTER_ID);
            assertThat(detail.displayStatus()).isEqualTo(RequestDisplayStatus.IN_PROGRESS);
            assertThat(detail.actions()).containsExactly(COMPLETE, CANCEL);
        }

        @Test
        void 취소된_요청은_취소_사유와_같은_내용으로_새_요청을_돌려준다() {
            CareRequest request = request();
            assignmentRepository.save(RequestAssignment.assign(request, MINJAE, 1, NOW.plusHours(24)));
            request.cancel(CancelReason.REQUESTER_CANCELED, NOW.minusMinutes(1));
            flushAndClear();

            CareRequestDetailResponse detail = detailService.getDetail(request.getId(), REQUESTER_ID);
            assertThat(detail.status()).isEqualTo(CareRequestStatus.CANCELED);
            assertThat(detail.cancelReason()).isEqualTo(CancelReason.REQUESTER_CANCELED);
            assertThat(detail.actions()).containsExactly(DUPLICATE);
        }

        @Test
        void 완료됐고_상황이_종료됐으면_지난_상황에서_보기를_돌려준다() {
            situation("ENDED");
            CareRequest request = completed();
            flushAndClear();

            CareRequestDetailResponse detail = detailService.getDetail(request.getId(), REQUESTER_ID);
            assertThat(detail.situation().status()).isEqualTo("ENDED");
            assertThat(detail.actions()).containsExactly(DUPLICATE, VIEW_PAST_SITUATION);
        }

        @Test
        void 상황을_찾을_수_없으면_situation은_null이다() {
            when(situationReader.findSituation(SITUATION_ID)).thenReturn(Optional.empty());
            CareRequest request = request();
            assignmentRepository.save(RequestAssignment.assign(request, MINJAE, 1, NOW.plusHours(24)));
            flushAndClear();

            assertThat(detailService.getDetail(request.getId(), REQUESTER_ID).situation()).isNull();
        }
    }

    // === 대상자 뷰 ===

    @Nested
    class 대상자_뷰 {

        @Test
        void 내_지정과_응답만_돌려주고_요청자_전용_필드는_null이다() {
            CareRequest request = request();
            assignmentRepository.save(RequestAssignment.assign(request, MINJAE, 1, NOW.plusHours(24), 24))
                    .respond(AssignmentResponseStatus.CHECK_LATER, NOW.minusMinutes(10));
            flushAndClear();

            CareRequestDetailResponse detail = detailService.getDetail(request.getId(), MINJAE);

            assertThat(detail.viewerRole()).isEqualTo(RequestViewerRole.TARGET);
            assertThat(detail.requester().name()).isEqualTo("김지우");
            assertThat(detail.situation()).isNotNull();
            assertThat(detail.currentAssignment()).isNull();
            assertThat(detail.assignments()).isNull();
            assertThat(detail.responseDeadlineHours()).isNull();
            assertThat(detail.noAssigneeReason()).isNull();
            assertThat(detail.latestProposal()).isNull();
            assertThat(detail.myAssignment().responseStatus()).isEqualTo(AssignmentResponseStatus.CHECK_LATER);
            assertThat(detail.myAssignment().responseDeadline()).isEqualTo(NOW.plusHours(24));
            assertThat(detail.myAssignment().closed()).isFalse();
            assertThat(detail.actions()).containsExactly(RESPOND);
        }

        @Test
        void 어려움으로_응답했어도_시작_전이면_응답을_바꿀_수_있다() {
            CareRequest request = request();
            assignmentRepository.save(RequestAssignment.assign(request, MINJAE, 1, NOW.minusMinutes(1)))
                    .respond(AssignmentResponseStatus.UNAVAILABLE, NOW.minusHours(1));
            flushAndClear();

            assertThat(detailService.getDetail(request.getId(), MINJAE).actions()).containsExactly(RESPOND);
        }

        @Test
        void 다른_사람에게_넘어간_이전_대상은_마감_정보를_받고_동작이_없다() {
            CareRequest request = request();
            assignmentRepository.save(RequestAssignment.assign(request, MINJAE, 1, NOW.plusHours(24)))
                    .close(AssignmentCloseReason.TARGET_CHANGED, NOW.minusMinutes(5));
            entityManager.flush();  // 마감을 먼저 반영 (요청당 열린 지정 1건 제약)
            assignmentRepository.save(RequestAssignment.assign(request, SEOYEON, 2, NOW.plusHours(24)));
            flushAndClear();

            CareRequestDetailResponse detail = detailService.getDetail(request.getId(), MINJAE);

            assertThat(detail.viewerRole()).isEqualTo(RequestViewerRole.TARGET);
            assertThat(detail.myAssignment().closed()).isTrue();
            assertThat(detail.myAssignment().closeReason()).isEqualTo(AssignmentCloseReason.TARGET_CHANGED);
            assertThat(detail.assignments()).isNull(); // 다음 대상이 누군지 알려 주지 않음
            assertThat(detail.actions()).isEmpty();
        }

        @Test
        void 시작_시각이_지나면_응답할_수_없다() {
            CareRequest request = careRequestRepository.save(CareRequest.createGeneral(SITUATION_ID, REQUESTER_ID,
                    HelpType.MEAL, null, "집", NOW.minusMinutes(10), NOW.plusHours(2), null));
            assignmentRepository.save(RequestAssignment.assign(request, MINJAE, 1, NOW.minusMinutes(10)));
            flushAndClear();

            assertThat(detailService.getDetail(request.getId(), MINJAE).actions()).isEmpty();
        }

        @Test
        void 만료된_요청은_동작이_없다() {
            CareRequest request = request();
            assignmentRepository.save(RequestAssignment.assign(request, MINJAE, 1, NOW.plusHours(24)))
                    .close(AssignmentCloseReason.REQUEST_ENDED, NOW);
            request.expire(NOW);
            flushAndClear();

            CareRequestDetailResponse detail = detailService.getDetail(request.getId(), MINJAE);
            assertThat(detail.status()).isEqualTo(CareRequestStatus.EXPIRED);
            assertThat(detail.actions()).isEmpty();
        }
    }

    // === 담당자 뷰 ===

    @Nested
    class 담당자_뷰 {

        @Test
        void 수락됨이면_수락_철회와_시작을_할_수_있다() {
            CareRequest request = request();
            accepted(request, MINJAE);
            flushAndClear();

            CareRequestDetailResponse detail = detailService.getDetail(request.getId(), MINJAE);
            assertThat(detail.viewerRole()).isEqualTo(RequestViewerRole.ASSIGNEE);
            assertThat(detail.displayStatus()).isEqualTo(RequestDisplayStatus.ACCEPTED);
            assertThat(detail.myAssignment().responseStatus()).isEqualTo(AssignmentResponseStatus.AVAILABLE);
            assertThat(detail.actions()).containsExactly(WITHDRAW, START);
        }

        @Test
        void 진행_중이면_완료만_할_수_있다() {
            CareRequest request = request();
            accepted(request, MINJAE);
            request.start(NOW.minusMinutes(5));
            flushAndClear();

            assertThat(detailService.getDetail(request.getId(), MINJAE).actions()).containsExactly(COMPLETE);
        }

        @Test
        void 재확인_대기_중이면_확인과_거절을_할_수_있고_변경_전후를_이름과_함께_돌려준다() {
            CareRequest request = request();
            RequestAssignment assignment = accepted(request, MINJAE);
            RequestChangeProposal proposal = proposalRepository.save(RequestChangeProposal.create(request, assignment,
                    Set.of(ChangeableField.PLACE, ChangeableField.INFORMATION_IDS), null, null,
                    "△△병원", List.of(20L, 12L, 77L), NOW.minusHours(1))); // 77: 그 사이 삭제된 항목
            flushAndClear();

            CareRequestDetailResponse detail = detailService.getDetail(request.getId(), MINJAE);

            assertThat(detail.actions()).containsExactly(WITHDRAW, START, ACCEPT_PROPOSAL, REJECT_PROPOSAL);
            ProposalView view = detail.latestProposal();
            assertThat(view.proposalId()).isEqualTo(proposal.getId());
            assertThat(view.status()).isEqualTo(ProposalStatus.PENDING);
            assertThat(view.invalidationReason()).isNull();
            assertThat(view.changedFields()).containsExactlyInAnyOrder(ChangeableField.PLACE, ChangeableField.INFORMATION_IDS);
            assertThat(view.responseDeadline()).isEqualTo(NOW.minusHours(1).plusHours(24));

            assertThat(view.before().place()).isEqualTo("○○병원");
            assertThat(view.before().startsAt()).isEqualTo(STARTS_AT);
            assertThat(view.before().informations()).extracting(InformationView::informationId)
                    .containsExactly(15L, 12L); // D가 준 순서 그대로
            assertThat(view.before().informations()).extracting(InformationView::name)
                    .containsExactly("출입 방법", "생년월일");

            assertThat(view.after().place()).isEqualTo("△△병원");
            assertThat(view.after().startsAt()).isEqualTo(STARTS_AT); // 바꾸지 않은 필드는 변경 전과 같은 값
            assertThat(view.after().endsAt()).isEqualTo(ENDS_AT);
            assertThat(view.after().informations()).extracting(InformationView::informationId)
                    .containsExactly(20L, 12L); // 수정안 순서, 이름이 없는(삭제된) 항목은 뺌
        }

        @Test
        void 재확인_기한이_지난_수정안은_확인할_수_없다() {
            CareRequest request = request();
            RequestAssignment assignment = accepted(request, MINJAE);
            proposalRepository.save(RequestChangeProposal.create(request, assignment, Set.of(ChangeableField.PLACE),
                    null, null, "△△병원", null, NOW.minusHours(25))); // 기한 = 1시간 전, 스케줄러 처리 전
            flushAndClear();

            assertThat(detailService.getDetail(request.getId(), MINJAE).actions())
                    .doesNotContain(ACCEPT_PROPOSAL, REJECT_PROPOSAL);
        }

        @Test
        void 수락을_철회한_이전_담당자는_다음_담당자의_수정안을_볼_수_없고_동작이_없다() {
            CareRequest request = request();
            RequestAssignment first = accepted(request, MINJAE);
            first.close(AssignmentCloseReason.ACCEPTANCE_WITHDRAWN, NOW.minusHours(3));
            request.revertToRequested();
            entityManager.flush();  // 마감을 먼저 반영 (요청당 열린 지정 1건 제약)
            RequestAssignment second = assignmentRepository.save(RequestAssignment.assign(request, SEOYEON, 2, NOW.plusHours(12)));
            second.respond(AssignmentResponseStatus.AVAILABLE, NOW.minusHours(2));
            request.accept();
            proposalRepository.save(RequestChangeProposal.create(request, second, Set.of(ChangeableField.PLACE),
                    null, null, "△△병원", null, NOW.minusHours(1)));
            flushAndClear();

            CareRequestDetailResponse previous = detailService.getDetail(request.getId(), MINJAE);
            assertThat(previous.viewerRole()).isEqualTo(RequestViewerRole.ASSIGNEE); // 수락한 적이 있음
            assertThat(previous.myAssignment().closed()).isTrue();
            assertThat(previous.latestProposal()).isNull();
            assertThat(previous.actions()).isEmpty();

            assertThat(detailService.getDetail(request.getId(), SEOYEON).latestProposal()).isNotNull();
        }

        @Test
        void 완료됐고_상황이_종료됐으면_지난_상황에서_보기를_돌려준다() {
            situation("ENDED");
            CareRequest request = completed();
            flushAndClear();

            assertThat(detailService.getDetail(request.getId(), MINJAE).actions()).containsExactly(VIEW_PAST_SITUATION);
        }
    }

    // === 요청자의 수정안 ===

    @Nested
    class 요청자의_수정안 {

        @Test
        void 재확인_대기_중이면_수정_철회를_할_수_있다() {
            CareRequest request = request();
            RequestAssignment assignment = accepted(request, MINJAE);
            proposalRepository.save(RequestChangeProposal.create(request, assignment,
                    Set.of(ChangeableField.STARTS_AT, ChangeableField.ENDS_AT), STARTS_AT.plusHours(1),
                    ENDS_AT.plusHours(1), null, null, NOW.minusHours(1)));
            flushAndClear();

            CareRequestDetailResponse detail = detailService.getDetail(request.getId(), REQUESTER_ID);
            assertThat(detail.actions()).containsExactly(CANCEL, EDIT, WITHDRAW_PROPOSAL);
            assertThat(detail.latestProposal().after().startsAt()).isEqualTo(STARTS_AT.plusHours(1));
            assertThat(detail.latestProposal().after().endsAt()).isEqualTo(ENDS_AT.plusHours(1));
            assertThat(detail.latestProposal().after().place()).isEqualTo("○○병원");
            assertThat(detail.latestProposal().after().informations()) // 공개 항목은 바꾸지 않아 변경 전과 같음
                    .isEqualTo(detail.latestProposal().before().informations());
        }

        @Test
        void 무효가_된_수정안은_이력에서_무효_사유를_꺼내_돌려준다() {
            CareRequest request = request();
            RequestAssignment assignment = accepted(request, MINJAE);
            RequestChangeProposal proposal = proposalRepository.save(RequestChangeProposal.create(request, assignment,
                    Set.of(ChangeableField.PLACE), null, null, "△△병원", null, NOW.minusHours(1)));
            proposal.invalidate();
            eventRepository.save(RequestChangeEvent.reconfirmationInvalidated(proposal,
                    InvalidationReason.REQUESTER_WITHDRAWN, REQUESTER_ID, NOW.minusMinutes(30)));
            flushAndClear();

            ProposalView view = detailService.getDetail(request.getId(), REQUESTER_ID).latestProposal();
            assertThat(view.status()).isEqualTo(ProposalStatus.INVALIDATED);
            assertThat(view.invalidationReason()).isEqualTo(InvalidationReason.REQUESTER_WITHDRAWN);
            assertThat(detailService.getDetail(request.getId(), REQUESTER_ID).actions()).doesNotContain(WITHDRAW_PROPOSAL);
        }
    }

    // === 관련 없는 사람 ===

    @Test
    void 관련_없는_사람이면_REQUEST_NOT_FOUND다() {
        CareRequest request = request();
        assignmentRepository.save(RequestAssignment.assign(request, MINJAE, 1, NOW.plusHours(24)));
        flushAndClear();

        assertThatThrownBy(() -> detailService.getDetail(request.getId(), STRANGER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CareRequestErrorCode.REQUEST_NOT_FOUND);
    }

    @Test
    void 없는_요청이면_REQUEST_NOT_FOUND다() {
        assertThatThrownBy(() -> detailService.getDetail(12345L, REQUESTER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CareRequestErrorCode.REQUEST_NOT_FOUND);
    }

    // === 도우미 ===

    private void situation(String status) {
        when(situationReader.findSituation(SITUATION_ID)).thenReturn(Optional.of(new SituationSummary(
                REQUESTER_ID, LocalDateTime.of(2026, 11, 9, 9, 0), LocalDateTime.of(2026, 11, 20, 18, 0), status)));
    }

    private CareRequest request() {
        return careRequestRepository.save(CareRequest.createGeneral(SITUATION_ID, REQUESTER_ID, HelpType.HOSPITAL,
                null, "○○병원", STARTS_AT, ENDS_AT, null));
    }

    /** 대상자가 도움 가능으로 응답해 수락된 상태 */
    private RequestAssignment accepted(CareRequest request, Long targetUserId) {
        RequestAssignment assignment = assignmentRepository.save(
                RequestAssignment.assign(request, targetUserId, 1, NOW.plusHours(12)));
        assignment.respond(AssignmentResponseStatus.AVAILABLE, NOW.minusHours(5));
        request.accept();
        return assignment;
    }

    /** 민재가 맡아 완료한 요청 (완료는 지정을 마감하지 않음) */
    private CareRequest completed() {
        CareRequest request = request();
        accepted(request, MINJAE);
        request.start(NOW.minusHours(3));
        request.complete(NOW.minusHours(1));
        return request;
    }

    /** A 조회 결과 형태(사용자 ID → 프로필)로 바꿉니다. */
    private static Map<Long, UserProfile> profiles(UserProfile... profiles) {
        return Arrays.stream(profiles).collect(Collectors.toMap(UserProfile::userId, profile -> profile));
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
