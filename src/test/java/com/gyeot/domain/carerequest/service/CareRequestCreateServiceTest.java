package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.carerequest.dto.request.CreateCareRequestRequest;
import com.gyeot.domain.carerequest.dto.response.CreateCareRequestResponse;
import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.RequestType;
import com.gyeot.domain.carerequest.event.CareRequestCreatedEvent;
import com.gyeot.domain.carerequest.exception.CareRequestErrorCode;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.domain.relation.entity.Relationship;
import com.gyeot.domain.relation.repository.RelationshipRepository;
import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
import com.gyeot.domain.safetycard.exception.SafetyCardErrorCode;
import com.gyeot.domain.safetycard.query.SafetyInformationSharingReader;
import com.gyeot.domain.situation.query.SituationParticipantReader;
import com.gyeot.domain.situation.query.SituationParticipantReader.SituationSummary;
import com.gyeot.global.config.JpaAuditingConfig;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 도움 요청 생성(#69)의 규칙을 실제 MySQL로 확인합니다.
 * A 관계는 실제 관계 행으로, B(상황·참여자)와 D(공개 항목 검증)는 목으로 대신합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAuditingConfig.class,
        SafetyInformationCipher.class, // 안심카드 엔티티의 암호화 Converter가 필요로 함
        RequestScheduleValidator.class,
        RequestTargetEligibility.class,
        CareRequestCreateService.class,
        CareRequestCreateServiceTest.FixedClockConfig.class // @Nested 안쪽 클래스는 바깥의 설정을 자동으로 못 찾아서 직접 등록
})
@ActiveProfiles("test")
@Testcontainers
@RecordApplicationEvents
public class CareRequestCreateServiceTest {
    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 9, 0);
    static final LocalDateTime STARTS_AT = NOW.plusDays(2);      // 11/12 09:00
    static final LocalDateTime ENDS_AT = STARTS_AT.plusHours(3); // 11/12 12:00
    // 상황 기간은 날짜시각 [시작, 종료) (#116). 0시가 아닌 시각으로 두어 날짜 단위로 계산하지 않는지 확인
    static final LocalDateTime SITUATION_START = LocalDateTime.of(2026, 11, 9, 9, 0);
    static final LocalDateTime SITUATION_END = LocalDateTime.of(2026, 11, 20, 18, 0);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            ZoneId seoul = ZoneId.of("Asia/Seoul");
            return Clock.fixed(NOW.atZone(seoul).toInstant(), seoul);
        }
    }

    private static final Long SITUATION_ID = 1L;
    private static final Long REQUESTER_ID = 10L;
    private static final Long MY_PERSON_ID = 20L;  // 내가 등록한 나의 사람, 참여자
    private static final Long GUARDIAN_ID = 30L;   // 서로의 보호자, 참여자
    private static final Long STRANGER_ID = 99L;

    @Autowired private CareRequestCreateService createService;
    @Autowired private CareRequestRepository careRequestRepository;
    @Autowired private RequestAssignmentRepository assignmentRepository;
    @Autowired private RelationshipRepository relationshipRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private ApplicationEvents events;

    @MockitoBean private SituationParticipantReader situationReader;
    @MockitoBean private SafetyInformationSharingReader sharingReader;

    @BeforeEach
    void setUp() {
        // 상황 11/9 ~ 11/20 (내가 만든 진행 중 상황), 나의 사람·보호자는 참여 중
        situation(REQUESTER_ID, "ACTIVE");
        when(situationReader.isActiveParticipant(SITUATION_ID, MY_PERSON_ID)).thenReturn(true);
        when(situationReader.isActiveParticipant(SITUATION_ID, GUARDIAN_ID)).thenReturn(true);

        relationshipRepository.save(Relationship.createMyPerson(REQUESTER_ID, MY_PERSON_ID, "동생"));
        relationshipRepository.save(Relationship.createGuardian(REQUESTER_ID, GUARDIAN_ID, 1)); // 보호자는 양방향 2줄
        relationshipRepository.save(Relationship.createGuardian(GUARDIAN_ID, REQUESTER_ID, 1));
    }

    // === 성공 ===

    @Nested
    class 성공 {

        @Test
        void 요청과_최초_지정을_저장하고_생성_이벤트를_발행한다() {
            CreateCareRequestResponse response = createService.create(REQUESTER_ID, body());
            flushAndClear();

            assertThat(response.status()).isEqualTo(CareRequestStatus.REQUESTED);
            assertThat(response.responseDeadline()).isEqualTo(NOW.plusHours(24)); // 보내지 않으면 24시간

            CareRequest saved = careRequestRepository.findById(response.requestId()).orElseThrow();
            assertThat(saved.getRequestType()).isEqualTo(RequestType.GENERAL);
            assertThat(saved.getSituationId()).isEqualTo(SITUATION_ID);
            assertThat(saved.getRequesterId()).isEqualTo(REQUESTER_ID);
            assertThat(saved.getHelpType()).isEqualTo(HelpType.HOSPITAL);
            assertThat(saved.getPlace()).isEqualTo("○○병원");
            assertThat(saved.getDescription()).isEqualTo("접수부터 함께해 주세요.");
            assertThat(saved.getStartsAt()).isEqualTo(STARTS_AT);
            assertThat(saved.getEndsAt()).isEqualTo(ENDS_AT);

            RequestAssignment assignment = onlyAssignment(saved.getId());
            assertThat(assignment.getTargetUserId()).isEqualTo(MY_PERSON_ID);
            assertThat(assignment.getAssignmentOrder()).isEqualTo(1);
            assertThat(assignment.getResponseDeadline()).isEqualTo(NOW.plusHours(24));
            assertThat(assignment.getResponseDeadlineHours()).isNull(); // 고르지 않음 → 기본값 규칙
            assertThat(assignment.getResponseStatus()).isNull();
            assertThat(assignment.isOpen()).isTrue();

            verify(sharingReader).validateSelection(REQUESTER_ID, List.of(12L, 15L));
            List<CareRequestCreatedEvent> created = events.stream(CareRequestCreatedEvent.class).toList();
            assertThat(created).hasSize(1);
            assertThat(created.get(0).requestId()).isEqualTo(saved.getId());
            assertThat(created.get(0).assignmentId()).isEqualTo(assignment.getId());
            assertThat(created.get(0).targetUserId()).isEqualTo(MY_PERSON_ID);
            assertThat(created.get(0).informationIds()).containsExactly(12L, 15L);
            assertThat(created.get(0).occurredAt()).isEqualTo(NOW);
        }

        @Test
        void 서로의_보호자에게도_보낼_수_있다() {
            CreateCareRequestResponse response = createService.create(REQUESTER_ID,
                    body(GUARDIAN_ID, STARTS_AT, ENDS_AT, null));
            assertThat(onlyAssignment(response.requestId()).getTargetUserId()).isEqualTo(GUARDIAN_ID);
        }

        @Test
        void 공개_항목이_0개여도_보낼_수_있다() {
            CreateCareRequestResponse response = createService.create(REQUESTER_ID, withInformationIds(List.of()));
            assertThat(response.status()).isEqualTo(CareRequestStatus.REQUESTED);
            assertThat(events.stream(CareRequestCreatedEvent.class).toList().get(0).informationIds()).isEmpty();
        }

        @Test
        void 고른_응답_기한을_저장한다() {
            CreateCareRequestResponse response = createService.create(REQUESTER_ID,
                    body(MY_PERSON_ID, STARTS_AT, ENDS_AT, 48));
            RequestAssignment assignment = onlyAssignment(response.requestId());
            assertThat(assignment.getResponseDeadline()).isEqualTo(NOW.plusHours(48));
            assertThat(assignment.getResponseDeadlineHours()).isEqualTo(48);
        }

        @Test
        void 응답_기한이_시작보다_늦으면_에러_없이_시작_시각으로_제한하고_고른_값은_그대로_남긴다() {
            LocalDateTime startsAt = NOW.plusHours(5);
            CreateCareRequestResponse response = createService.create(REQUESTER_ID,
                    body(MY_PERSON_ID, startsAt, startsAt.plusHours(2), 24));
            RequestAssignment assignment = onlyAssignment(response.requestId());
            assertThat(response.responseDeadline()).isEqualTo(startsAt);
            assertThat(assignment.getResponseDeadline()).isEqualTo(startsAt);
            assertThat(assignment.getResponseDeadlineHours()).isEqualTo(24);
        }

        @Test
        void 응답_기한_경계값_1시간과_72시간은_허용한다() {
            LocalDateTime startsAt = NOW.plusDays(4);
            assertThat(onlyAssignment(createService.create(REQUESTER_ID,
                    body(MY_PERSON_ID, startsAt, startsAt.plusHours(1), 1)).requestId())
                    .getResponseDeadline()).isEqualTo(NOW.plusHours(1));
            assertThat(onlyAssignment(createService.create(REQUESTER_ID,
                    body(GUARDIAN_ID, startsAt, startsAt.plusHours(1), 72)).requestId())
                    .getResponseDeadline()).isEqualTo(NOW.plusHours(72));
        }

        @Test
        void 부탁_내용이_없거나_공백이면_null로_저장한다() {
            Long noDescription = createService.create(REQUESTER_ID, withDescription(null)).requestId();
            Long blank = createService.create(REQUESTER_ID, withDescription("   ")).requestId();
            flushAndClear();
            assertThat(careRequestRepository.findById(noDescription).orElseThrow().getDescription()).isNull();
            assertThat(careRequestRepository.findById(blank).orElseThrow().getDescription()).isNull();
        }

        @Test
        void 장소와_부탁_내용은_앞뒤_공백을_빼고_글자_수를_센다() {
            String place = "  " + "가".repeat(CareRequest.MAX_PLACE_LENGTH) + "  ";
            String description = " " + "나".repeat(CareRequest.MAX_DESCRIPTION_LENGTH) + "\n";
            Long requestId = createService.create(REQUESTER_ID, body(MY_PERSON_ID, STARTS_AT, ENDS_AT, null,
                    place, description, List.of())).requestId();
            flushAndClear();
            CareRequest saved = careRequestRepository.findById(requestId).orElseThrow();
            assertThat(saved.getPlace()).hasSize(CareRequest.MAX_PLACE_LENGTH);
            assertThat(saved.getDescription()).hasSize(CareRequest.MAX_DESCRIPTION_LENGTH);
        }

        @Test
        void 시작이_정확히_1시간_뒤면_허용한다() {
            LocalDateTime startsAt = NOW.plus(CareRequest.MIN_START_LEAD_TIME);
            CreateCareRequestResponse response = createService.create(REQUESTER_ID,
                    body(MY_PERSON_ID, startsAt, startsAt.plusHours(1), null));
            assertThat(response.responseDeadline()).isEqualTo(startsAt); // 24시간보다 시작이 먼저
        }

        @Test
        void 요청_종료가_상황_종료_시각과_같으면_허용한다() {
            // 상황 시작 시각은 이미 지났으므로 종료 시각만 확인 (종료 시각과 같아도 됨)
            LocalDateTime endOfSituation = SITUATION_END;
            assertThat(createService.create(REQUESTER_ID,
                    body(MY_PERSON_ID, endOfSituation.minusHours(2), endOfSituation, null)).status())
                    .isEqualTo(CareRequestStatus.REQUESTED);
        }
    }

    // === 입력 확인 ===

    @Nested
    class 입력_확인 {

        @Test
        void 응답_기한이_1시간보다_짧거나_72시간보다_길면_REQUEST_DEADLINE_INVALID다() {
            assertError(() -> createService.create(REQUESTER_ID, body(MY_PERSON_ID, STARTS_AT, ENDS_AT, 0)),
                    CareRequestErrorCode.REQUEST_DEADLINE_INVALID);
            assertError(() -> createService.create(REQUESTER_ID, body(MY_PERSON_ID, STARTS_AT, ENDS_AT, 73)),
                    CareRequestErrorCode.REQUEST_DEADLINE_INVALID);
            assertError(() -> createService.create(REQUESTER_ID, body(MY_PERSON_ID, STARTS_AT, ENDS_AT, -1)),
                    CareRequestErrorCode.REQUEST_DEADLINE_INVALID);
            assertNothingSaved();
        }

        @Test
        void 장소가_100자를_넘으면_REQUEST_PLACE_TOO_LONG이다() {
            assertError(() -> createService.create(REQUESTER_ID, body(MY_PERSON_ID, STARTS_AT, ENDS_AT, null,
                            "가".repeat(CareRequest.MAX_PLACE_LENGTH + 1), null, List.of())),
                    CareRequestErrorCode.REQUEST_PLACE_TOO_LONG);
            assertNothingSaved();
        }

        @Test
        void 부탁_내용이_500자를_넘으면_REQUEST_DESCRIPTION_TOO_LONG이다() {
            assertError(() -> createService.create(REQUESTER_ID,
                            withDescription("나".repeat(CareRequest.MAX_DESCRIPTION_LENGTH + 1))),
                    CareRequestErrorCode.REQUEST_DESCRIPTION_TOO_LONG);
            assertNothingSaved();
        }
    }

    // === 상황 확인 ===

    @Nested
    class 상황_확인 {

        @Test
        void 없는_상황이면_REQUEST_SITUATION_INVALID다() {
            when(situationReader.findSituation(SITUATION_ID)).thenReturn(Optional.empty());
            assertError(() -> createService.create(REQUESTER_ID, body()), CareRequestErrorCode.REQUEST_SITUATION_INVALID);
            assertNothingSaved();
        }

        @Test
        void 다른_사람이_만든_상황이면_REQUEST_SITUATION_INVALID다() {
            situation(STRANGER_ID, "ACTIVE");
            assertError(() -> createService.create(REQUESTER_ID, body()), CareRequestErrorCode.REQUEST_SITUATION_INVALID);
        }

        @Test
        void 다른_사람의_종료된_상황이면_종료_여부를_알리지_않고_REQUEST_SITUATION_INVALID다() {
            situation(STRANGER_ID, "ENDED");
            assertError(() -> createService.create(REQUESTER_ID, body()), CareRequestErrorCode.REQUEST_SITUATION_INVALID);
        }

        @Test
        void 내_상황이_종료됐으면_REQUEST_SITUATION_ENDED다() {
            situation(REQUESTER_ID, "ENDED");
            assertError(() -> createService.create(REQUESTER_ID, body()), CareRequestErrorCode.REQUEST_SITUATION_ENDED);
            assertNothingSaved();
        }
    }

    // === 대상 확인 ===

    @Nested
    class 대상_확인 {

        @Test
        void 본인에게는_보낼_수_없다() {
            when(situationReader.isActiveParticipant(SITUATION_ID, REQUESTER_ID)).thenReturn(true);
            assertError(() -> createService.create(REQUESTER_ID, body(REQUESTER_ID, STARTS_AT, ENDS_AT, null)),
                    CareRequestErrorCode.REQUEST_TARGET_INVALID);
        }

        @Test
        void 상황_참여자가_아니면_보낼_수_없다() {
            when(situationReader.isActiveParticipant(SITUATION_ID, MY_PERSON_ID)).thenReturn(false); // 나감·제외
            assertError(() -> createService.create(REQUESTER_ID, body()), CareRequestErrorCode.REQUEST_TARGET_INVALID);
            assertNothingSaved();
        }

        @Test
        void 관계가_없으면_참여자여도_보낼_수_없다() {
            when(situationReader.isActiveParticipant(SITUATION_ID, STRANGER_ID)).thenReturn(true);
            assertError(() -> createService.create(REQUESTER_ID, body(STRANGER_ID, STARTS_AT, ENDS_AT, null)),
                    CareRequestErrorCode.REQUEST_TARGET_INVALID);
        }

        @Test
        void 상대가_나를_나의_사람으로_등록한_반대_방향_관계만_있으면_보낼_수_없다() {
            Long registeredMe = 40L;
            relationshipRepository.save(Relationship.createMyPerson(registeredMe, REQUESTER_ID, "언니"));
            when(situationReader.isActiveParticipant(SITUATION_ID, registeredMe)).thenReturn(true);
            assertError(() -> createService.create(REQUESTER_ID, body(registeredMe, STARTS_AT, ENDS_AT, null)),
                    CareRequestErrorCode.REQUEST_TARGET_INVALID);
        }

        @Test
        void 해제된_관계면_보낼_수_없다() {
            Long ended = 50L;
            Relationship relationship = Relationship.createMyPerson(REQUESTER_ID, ended, "친구");
            relationship.end(NOW.minusDays(1));
            relationshipRepository.save(relationship);
            when(situationReader.isActiveParticipant(SITUATION_ID, ended)).thenReturn(true);
            assertError(() -> createService.create(REQUESTER_ID, body(ended, STARTS_AT, ENDS_AT, null)),
                    CareRequestErrorCode.REQUEST_TARGET_INVALID);
        }
    }

    // === 일정 확인 ===

    @Nested
    class 일정_확인 {

        @Test
        void 시작이_1시간_안쪽이면_REQUEST_START_TOO_SOON이다() {
            LocalDateTime startsAt = NOW.plusMinutes(59);
            assertError(() -> createService.create(REQUESTER_ID, body(MY_PERSON_ID, startsAt, startsAt.plusHours(1), null)),
                    CareRequestErrorCode.REQUEST_START_TOO_SOON);
            assertNothingSaved();
        }

        @Test
        void 시작이_이미_지났으면_REQUEST_START_TOO_SOON이다() {
            LocalDateTime startsAt = NOW.minusHours(1);
            assertError(() -> createService.create(REQUESTER_ID, body(MY_PERSON_ID, startsAt, startsAt.plusHours(3), null)),
                    CareRequestErrorCode.REQUEST_START_TOO_SOON);
        }

        @Test
        void 종료가_시작보다_늦지_않으면_REQUEST_SCHEDULE_INVALID다() {
            assertError(() -> createService.create(REQUESTER_ID, body(MY_PERSON_ID, STARTS_AT, STARTS_AT, null)),
                    CareRequestErrorCode.REQUEST_SCHEDULE_INVALID);
            assertError(() -> createService.create(REQUESTER_ID,
                            body(MY_PERSON_ID, STARTS_AT, STARTS_AT.minusHours(1), null)),
                    CareRequestErrorCode.REQUEST_SCHEDULE_INVALID);
        }

        @Test
        void 상황_종료_시각을_넘기면_REQUEST_SCHEDULE_INVALID다() {
            LocalDateTime endOfSituation = SITUATION_END; // 같은 날 18:00 이후는 날짜가 같아도 거부
            assertError(() -> createService.create(REQUESTER_ID,
                            body(MY_PERSON_ID, endOfSituation.minusHours(1), endOfSituation.plusMinutes(1), null)),
                    CareRequestErrorCode.REQUEST_SCHEDULE_INVALID);
            assertNothingSaved();
        }
    }

    // === 공개 항목 확인 (D) ===

    @Test
    void D가_공개_항목을_거부하면_그_에러를_그대로_돌려주고_저장하지_않는다() {
        doThrow(new BusinessException(SafetyCardErrorCode.SAFETY_INFO_INVALID_SELECTION))
                .when(sharingReader).validateSelection(eq(REQUESTER_ID), anyList());
        assertError(() -> createService.create(REQUESTER_ID, body()), SafetyCardErrorCode.SAFETY_INFO_INVALID_SELECTION);
        assertNothingSaved();
        assertThat(events.stream(CareRequestCreatedEvent.class)).isEmpty();
    }

    @Test
    void 대상이_잘못되면_D를_부르기_전에_멈춘다() {
        when(situationReader.isActiveParticipant(SITUATION_ID, MY_PERSON_ID)).thenReturn(false);
        assertError(() -> createService.create(REQUESTER_ID, body()), CareRequestErrorCode.REQUEST_TARGET_INVALID);
        verify(sharingReader, org.mockito.Mockito.never()).validateSelection(any(), any());
    }

    // === 도우미 ===

    private void situation(Long creatorId, String status) {
        when(situationReader.findSituation(SITUATION_ID)).thenReturn(Optional.of(
                new SituationSummary(creatorId, SITUATION_START, SITUATION_END, status)));
    }

    private CreateCareRequestRequest body() {
        return body(MY_PERSON_ID, STARTS_AT, ENDS_AT, null);
    }

    private CreateCareRequestRequest body(Long targetUserId, LocalDateTime startsAt, LocalDateTime endsAt,
                                          Integer deadlineHours) {
        return body(targetUserId, startsAt, endsAt, deadlineHours, "○○병원", "접수부터 함께해 주세요.",
                List.of(12L, 15L));
    }

    private CreateCareRequestRequest withDescription(String description) {
        return body(MY_PERSON_ID, STARTS_AT, ENDS_AT, null, "○○병원", description, List.of());
    }

    private CreateCareRequestRequest withInformationIds(List<Long> informationIds) {
        return body(MY_PERSON_ID, STARTS_AT, ENDS_AT, null, "○○병원", null, informationIds);
    }

    private CreateCareRequestRequest body(Long targetUserId, LocalDateTime startsAt, LocalDateTime endsAt,
                                          Integer deadlineHours, String place, String description,
                                          List<Long> informationIds) {
        return new CreateCareRequestRequest(SITUATION_ID, HelpType.HOSPITAL, startsAt, endsAt, place, description,
                targetUserId, deadlineHours, informationIds);
    }

    private RequestAssignment onlyAssignment(Long requestId) {
        List<RequestAssignment> assignments = assignmentRepository.findAllByCareRequestIdOrderByAssignmentOrderAsc(requestId);
        assertThat(assignments).hasSize(1);
        return assignments.get(0);
    }

    private void assertNothingSaved() {
        assertThat(careRequestRepository.count()).isZero();
        assertThat(assignmentRepository.count()).isZero();
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
