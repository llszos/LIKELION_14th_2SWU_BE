package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.carerequest.dto.response.TargetCandidatesResponse;
import com.gyeot.domain.carerequest.dto.response.TargetCandidatesResponse.CandidateView;
import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.exception.CareRequestErrorCode;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.domain.relation.entity.Relationship;
import com.gyeot.domain.relation.repository.RelationshipRepository;
import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
import com.gyeot.domain.situation.query.SituationParticipantReader;
import com.gyeot.domain.situation.query.SituationParticipantReader.SituationSummary;
import com.gyeot.domain.user.query.UserProfileQuery;
import com.gyeot.domain.user.query.UserProfileQuery.UserProfile;
import com.gyeot.global.config.JpaAuditingConfig;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import com.gyeot.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 대상 후보 조회(#108)를 실제 MySQL로 확인합니다.
 * A 관계는 실제 관계 행으로, B(상황·참여자)와 A 이름은 목으로 대신합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAuditingConfig.class,
        SafetyInformationCipher.class, // 안심카드 엔티티의 암호화 Converter가 필요로 함
        CareRequestFinder.class,
        RequestTargetEligibility.class,
        RequestUserNames.class,
        TargetCandidateService.class
})
@ActiveProfiles("test")
@Testcontainers
public class TargetCandidateServiceTest {
    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 9, 0);

    private static final Long SITUATION_ID = 1L;
    private static final Long OTHER_SITUATION_ID = 2L;
    private static final Long REQUESTER_ID = 10L;
    private static final Long MINJAE = 20L;  // 나의 사람
    private static final Long SEOYEON = 30L;  // 나의 사람
    private static final Long JIHO = 40L;     // 서로의 보호자 (보호자 순서 2)
    private static final Long HANA = 50L;     // 서로의 보호자 (보호자 순서 1)
    private static final Long STRANGER = 60L; // 참여자지만 관계 없음
    private static final Long OUTSIDER = 70L; // 나의 사람이지만 참여자 아님

    @Autowired private TargetCandidateService candidateService;
    @Autowired private RequestTargetEligibility targetEligibility;
    @Autowired private CareRequestRepository careRequestRepository;
    @Autowired private RequestAssignmentRepository assignmentRepository;
    @Autowired private RelationshipRepository relationshipRepository;
    @Autowired private EntityManager entityManager;

    @MockitoBean private SituationParticipantReader situationReader;
    @MockitoBean private UserProfileQuery userProfileQuery;

    @BeforeEach
    void setUp() {
        situation(SITUATION_ID, REQUESTER_ID, "ACTIVE");
        // 참여 순서: 요청자(OWNER) → 민재 → 지호 → 낯선 사람 → 서연 → 하나 (B가 joinedAt 순으로 줌)
        when(situationReader.findActiveParticipantIds(SITUATION_ID))
                .thenReturn(List.of(REQUESTER_ID, MINJAE, JIHO, STRANGER, SEOYEON, HANA));
        when(situationReader.isActiveParticipant(eq(SITUATION_ID), anyLong())).thenAnswer(invocation ->
                List.of(REQUESTER_ID, MINJAE, JIHO, STRANGER, SEOYEON, HANA).contains(invocation.<Long>getArgument(1)));
        when(userProfileQuery.findProfilesByUserIds(anyCollection())).thenReturn(profiles(
                new UserProfile(MINJAE, "박민재", false), new UserProfile(SEOYEON, "이서연", false),
                new UserProfile(JIHO, "최지호", false), new UserProfile(HANA, null, true))); // 하나는 탈퇴

        relationshipRepository.save(Relationship.createMyPerson(REQUESTER_ID, MINJAE, "동생"));
        relationshipRepository.save(Relationship.createMyPerson(REQUESTER_ID, SEOYEON, "친구"));
        relationshipRepository.save(Relationship.createMyPerson(REQUESTER_ID, OUTSIDER, "이웃"));
        relationshipRepository.save(Relationship.createGuardian(REQUESTER_ID, JIHO, 2));
        relationshipRepository.save(Relationship.createGuardian(JIHO, REQUESTER_ID, 1));
        relationshipRepository.save(Relationship.createGuardian(REQUESTER_ID, HANA, 1));
        relationshipRepository.save(Relationship.createGuardian(HANA, REQUESTER_ID, 1));
    }

    // === 요청 작성 (situationId) ===

    @Nested
    class 요청_작성 {

        @Test
        void 참여자_중_나의_사람과_보호자만_보호자_먼저_돌려준다() {
            TargetCandidatesResponse response = candidateService.findCandidates(REQUESTER_ID, SITUATION_ID, null);

            // 보호자: 보호자 순서(하나 1 → 지호 2), 나의 사람: 참여 순(민재 → 서연)
            // 본인·관계 없는 참여자(낯선 사람)·참여자 아닌 나의 사람(이웃)은 빠짐
            assertThat(response.items())
                    .extracting(CandidateView::userId, CandidateView::name, CandidateView::isGuardian)
                    .containsExactly(
                            tuple(HANA, RequestUserNames.WITHDRAWN_USER_NAME, true),
                            tuple(JIHO, "최지호", true),
                            tuple(MINJAE, "박민재", false),
                            tuple(SEOYEON, "이서연", false));
        }

        @Test
        void 상대가_나를_등록한_반대_방향_관계만_있으면_후보가_아니다() {
            Long registeredMe = 80L;
            relationshipRepository.save(Relationship.createMyPerson(registeredMe, REQUESTER_ID, "언니"));
            when(situationReader.findActiveParticipantIds(SITUATION_ID)).thenReturn(List.of(REQUESTER_ID, registeredMe));

            assertThat(candidateService.findCandidates(REQUESTER_ID, SITUATION_ID, null).items()).isEmpty();
        }

        @Test
        void 해제된_관계는_후보가_아니다() {
            relationshipRepository.findAll().stream()
                    .filter(relationship -> relationship.getTargetUserId().equals(MINJAE))
                    .forEach(relationship -> relationship.end(NOW.minusDays(1)));
            flushAndClear();

            assertThat(candidateService.findCandidates(REQUESTER_ID, SITUATION_ID, null).items())
                    .extracting(CandidateView::userId).doesNotContain(MINJAE);
        }

        @Test
        void B가_같은_사람을_두_번_줘도_한_번만_담는다() {
            when(situationReader.findActiveParticipantIds(SITUATION_ID))
                    .thenReturn(List.of(REQUESTER_ID, MINJAE, MINJAE, SEOYEON));

            assertThat(candidateService.findCandidates(REQUESTER_ID, SITUATION_ID, null).items())
                    .extracting(CandidateView::userId).containsExactly(MINJAE, SEOYEON);
        }

        @Test
        void 후보가_없으면_빈_목록이다() {
            when(situationReader.findActiveParticipantIds(SITUATION_ID)).thenReturn(List.of(REQUESTER_ID));

            assertThat(candidateService.findCandidates(REQUESTER_ID, SITUATION_ID, null).items()).isEmpty();
        }

        @Test
        void 후보_목록은_요청_생성의_대상_자격_판단과_같은_기준이다() {
            List<Long> candidateIds = targetEligibility.findCandidates(SITUATION_ID, REQUESTER_ID).stream()
                    .map(RequestTargetEligibility.Candidate::userId).toList();

            for (Long userId : List.of(REQUESTER_ID, MINJAE, SEOYEON, JIHO, HANA, STRANGER, OUTSIDER)) {
                assertThat(candidateIds.contains(userId))
                        .as("userId=%d", userId)
                        .isEqualTo(targetEligibility.isEligible(SITUATION_ID, REQUESTER_ID, userId));
            }
        }
    }

    // === 상황 확인 ===

    @Nested
    class 상황_확인 {

        @Test
        void 없는_상황이면_REQUEST_SITUATION_INVALID다() {
            when(situationReader.findSituation(SITUATION_ID)).thenReturn(Optional.empty());
            assertError(() -> candidateService.findCandidates(REQUESTER_ID, SITUATION_ID, null),
                    CareRequestErrorCode.REQUEST_SITUATION_INVALID);
        }

        @Test
        void 다른_사람_상황이면_종료됐어도_REQUEST_SITUATION_INVALID다() {
            situation(SITUATION_ID, MINJAE, "ENDED");
            assertError(() -> candidateService.findCandidates(REQUESTER_ID, SITUATION_ID, null),
                    CareRequestErrorCode.REQUEST_SITUATION_INVALID);
        }

        @Test
        void 내_상황이_종료됐으면_REQUEST_SITUATION_ENDED다() {
            situation(SITUATION_ID, REQUESTER_ID, "ENDED");
            assertError(() -> candidateService.findCandidates(REQUESTER_ID, SITUATION_ID, null),
                    CareRequestErrorCode.REQUEST_SITUATION_ENDED);
        }

        @Test
        void 상황과_요청을_둘_다_보내지_않으면_INVALID_INPUT이다() {
            assertError(() -> candidateService.findCandidates(REQUESTER_ID, null, null), CommonErrorCode.INVALID_INPUT);
        }
    }

    // === 다시 부탁하기 (requestId) ===

    @Nested
    class 다시_부탁하기 {

        @Test
        void 이_요청에_부탁했던_사람은_현재_대상까지_모두_뺀다() {
            CareRequest request = request(SITUATION_ID);
            RequestAssignment first = assignmentRepository.save(RequestAssignment.assign(request, MINJAE, 1, NOW.plusHours(12)));
            first.respond(AssignmentResponseStatus.UNAVAILABLE, NOW.minusHours(2));
            first.close(AssignmentCloseReason.TARGET_CHANGED, NOW.minusHours(1));
            entityManager.flush();  // 마감을 먼저 반영 (요청당 열린 지정 1건 제약)
            assignmentRepository.save(RequestAssignment.assign(request, HANA, 2, NOW.plusHours(12))); // 현재 대상
            flushAndClear();

            assertThat(candidateService.findCandidates(REQUESTER_ID, null, request.getId()).items())
                    .extracting(CandidateView::userId).containsExactly(JIHO, SEOYEON);
        }

        @Test
        void 상황도_함께_보내면_요청의_상황과_같아야_한다() {
            CareRequest request = request(SITUATION_ID);
            assignmentRepository.save(RequestAssignment.assign(request, MINJAE, 1, NOW.plusHours(12)));
            flushAndClear();

            assertThat(candidateService.findCandidates(REQUESTER_ID, SITUATION_ID, request.getId()).items())
                    .extracting(CandidateView::userId).doesNotContain(MINJAE);
            assertError(() -> candidateService.findCandidates(REQUESTER_ID, OTHER_SITUATION_ID, request.getId()),
                    CareRequestErrorCode.REQUEST_NOT_FOUND);
        }

        @Test
        void 대상자는_후보를_볼_수_없어_FORBIDDEN이다() {
            CareRequest request = request(SITUATION_ID);
            assignmentRepository.save(RequestAssignment.assign(request, MINJAE, 1, NOW.plusHours(12)));
            flushAndClear();

            assertError(() -> candidateService.findCandidates(MINJAE, null, request.getId()), CommonErrorCode.FORBIDDEN);
        }

        @Test
        void 관련_없는_요청이면_REQUEST_NOT_FOUND다() {
            CareRequest request = request(SITUATION_ID);
            assignmentRepository.save(RequestAssignment.assign(request, MINJAE, 1, NOW.plusHours(12)));
            flushAndClear();

            assertError(() -> candidateService.findCandidates(STRANGER, null, request.getId()),
                    CareRequestErrorCode.REQUEST_NOT_FOUND);
            assertError(() -> candidateService.findCandidates(REQUESTER_ID, null, 12345L),
                    CareRequestErrorCode.REQUEST_NOT_FOUND);
        }

        @Test
        void 요청의_상황이_종료됐으면_REQUEST_SITUATION_ENDED다() {
            CareRequest request = request(SITUATION_ID);
            assignmentRepository.save(RequestAssignment.assign(request, MINJAE, 1, NOW.plusHours(12)));
            flushAndClear();
            situation(SITUATION_ID, REQUESTER_ID, "ENDED");

            assertError(() -> candidateService.findCandidates(REQUESTER_ID, null, request.getId()),
                    CareRequestErrorCode.REQUEST_SITUATION_ENDED);
        }
    }

    // === 도우미 ===

    private void situation(Long situationId, Long creatorId, String status) {
        when(situationReader.findSituation(situationId)).thenReturn(Optional.of(new SituationSummary(
                creatorId, LocalDateTime.of(2026, 11, 9, 9, 0), LocalDateTime.of(2026, 11, 20, 18, 0), status)));
    }

    private CareRequest request(Long situationId) {
        return careRequestRepository.save(CareRequest.createGeneral(situationId, REQUESTER_ID, HelpType.HOSPITAL,
                null, "○○병원", NOW.plusDays(2), NOW.plusDays(2).plusHours(3), null));
    }

    /** A 조회 결과 형태(사용자 ID → 프로필)로 바꿉니다. */
    private static Map<Long, UserProfile> profiles(UserProfile... profiles) {
        return Arrays.stream(profiles).collect(Collectors.toMap(UserProfile::userId, profile -> profile));
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private void assertError(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }
}
