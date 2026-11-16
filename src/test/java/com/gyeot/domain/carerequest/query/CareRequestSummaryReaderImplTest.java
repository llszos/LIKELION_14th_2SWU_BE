package com.gyeot.domain.carerequest.query;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.entity.RequestChangeProposal;
import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import com.gyeot.domain.carerequest.enums.CancelReason;
import com.gyeot.domain.carerequest.enums.ChangeableField;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.HelpRecord;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.HelpRecordFilter;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.HomeRequestOverview;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.PastRequestRecord;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.PastSituationStats;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.RequestSummary;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.ResponseNeededItem;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.ResponseNeededType;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.SituationRequestStats;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.UpcomingSchedule;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeProposalRepository;
import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
import com.gyeot.global.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
import static org.assertj.core.api.Assertions.tuple;

/**
 * B 화면용 요청 요약 조회(CareRequestSummaryReader, #67)를 실제 MySQL로 확인합니다.
 * 요청·지정·수정안을 저장한 뒤 상황 통계, 상황 상세 목록, 홈, 지난 상황, 도움 기록이 맞게 나오는지 봅니다.
 * @Import로 테스트할 구현체를 직접 등록합니다. (@DataJpaTest는 @Service를 자동으로 띄우지 않음)
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, SafetyInformationCipher.class, CareRequestSummaryReaderImpl.class})
@ActiveProfiles("test")
@Testcontainers
public class CareRequestSummaryReaderImplTest {
    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 9, 0);
    private static final Long SITUATION_ID = 1L;
    private static final Long OTHER_SITUATION_ID = 2L;
    private static final Long EMPTY_SITUATION_ID = 3L;
    private static final Long REQUESTER_ID = 10L; // 상황 OWNER = 요청자
    private static final Long MINJAE = 20L;
    private static final Long SEOYEON = 30L;
    private static final Long STRANGER_ID = 99L;

    @Autowired
    private CareRequestSummaryReader reader; // 인터페이스 타입으로 받음 (B가 쓰는 방식)
    @Autowired
    private CareRequestRepository careRequestRepository;
    @Autowired
    private RequestAssignmentRepository assignmentRepository;
    @Autowired
    private RequestChangeProposalRepository proposalRepository;
    @Autowired
    private EntityManager entityManager;

    // === S-00 상황 탭, H-00 홈: 상황 카드 통계 ===
    @Nested
    class 상황_통계 {

        @Test
        void 끝나지_않은_요청을_화면_상태별로_세고_끝난_요청은_세지_않는다() {
            waiting(request(SITUATION_ID, 1), MINJAE);                 // 응답 대기
            unavailable(request(SITUATION_ID, 2), MINJAE);             // 담당자 없음 (어려움)
            deadlinePassed(request(SITUATION_ID, 3), SEOYEON);         // 담당자 없음 (기한 지남)
            accepted(request(SITUATION_ID, 4), MINJAE);                // 수락됨
            inProgress(request(SITUATION_ID, 5), SEOYEON);             // 진행 중
            completed(request(SITUATION_ID, 6), MINJAE);               // 완료 → 제외
            canceledBeforeStart(request(SITUATION_ID, 7), MINJAE);     // 취소 → 제외
            flushAndClear();

            SituationRequestStats stats = reader.getSituationStats(List.of(SITUATION_ID), NOW).get(SITUATION_ID);

            assertThat(stats.waitingCount()).isEqualTo(1);
            assertThat(stats.noAssigneeCount()).isEqualTo(2);
            assertThat(stats.acceptedCount()).isEqualTo(1);
            assertThat(stats.inProgressCount()).isEqualTo(1);
        }

        @Test
        void 다음_일정은_수락됨_진행_중_요청_중_시작이_가장_빠른_것이다() {
            accepted(request(SITUATION_ID, 48), MINJAE);
            CareRequest earliest = request(SITUATION_ID, 24);
            accepted(earliest, SEOYEON);
            waiting(request(SITUATION_ID, 2), MINJAE); // 응답 대기는 일정이 아님
            flushAndClear();

            UpcomingSchedule next = reader.getSituationStats(List.of(SITUATION_ID), NOW).get(SITUATION_ID).nextSchedule();

            assertThat(next.requestId()).isEqualTo(earliest.getId());
            assertThat(next.startsAt()).isEqualTo(NOW.plusHours(24));
            assertThat(next.place()).isEqualTo("○○병원");
            assertThat(next.requesterId()).isEqualTo(REQUESTER_ID);
            assertThat(next.assigneeId()).isEqualTo(SEOYEON);
        }

        @Test
        void 여러_상황을_한_번에_조회하고_요청이_없는_상황은_0건으로_채운다() {
            waiting(request(SITUATION_ID, 1), MINJAE);
            accepted(request(OTHER_SITUATION_ID, 1), MINJAE);
            flushAndClear();

            Map<Long, SituationRequestStats> stats =
                    reader.getSituationStats(List.of(SITUATION_ID, OTHER_SITUATION_ID, EMPTY_SITUATION_ID), NOW);

            assertThat(stats.keySet()).containsExactly(SITUATION_ID, OTHER_SITUATION_ID, EMPTY_SITUATION_ID);
            assertThat(stats.get(SITUATION_ID).waitingCount()).isEqualTo(1);
            assertThat(stats.get(SITUATION_ID).acceptedCount()).isZero();
            assertThat(stats.get(OTHER_SITUATION_ID).acceptedCount()).isEqualTo(1);
            assertThat(stats.get(EMPTY_SITUATION_ID)).isEqualTo(SituationRequestStats.empty());
        }

        @Test
        void 빈_목록이면_빈_결과다() {
            assertThat(reader.getSituationStats(List.of(), NOW)).isEmpty();
            assertThat(reader.getSituationStats(null, NOW)).isEmpty();
        }
    }

    // === H-03 상황 상세: 요청 목록 ===

    @Nested
    class 상황_상세_목록 {

        @Test
        void 요청자는_상황의_요청을_모두_시작_시각_순으로_본다() {
            CareRequest later = request(SITUATION_ID, 48);
            accepted(later, MINJAE);
            CareRequest earlier = request(SITUATION_ID, 24);
            waiting(earlier, SEOYEON);
            CareRequest ended = request(SITUATION_ID, 30);
            canceledBeforeStart(ended, MINJAE);
            request(OTHER_SITUATION_ID, 1); // 다른 상황 → 제외
            flushAndClear();

            List<RequestSummary> list = reader.getSituationRequests(SITUATION_ID, REQUESTER_ID, NOW);

            assertThat(list).extracting(RequestSummary::requestId)
                    .containsExactly(earlier.getId(), ended.getId(), later.getId());
            assertThat(list).extracting(RequestSummary::displayStatus).containsExactly(
                    RequestDisplayStatus.WAITING, RequestDisplayStatus.CANCELED, RequestDisplayStatus.ACCEPTED);
            assertThat(list.get(0).helpType()).isEqualTo(HelpType.HOSPITAL);
            assertThat(list.get(0).startsAt()).isEqualTo(NOW.plusHours(24));
            assertThat(list.get(0).endsAt()).isEqualTo(NOW.plusHours(27));
            assertThat(list.get(0).requesterId()).isEqualTo(REQUESTER_ID);
        }

        @Test
        void targetUserId는_가장_최근_지정의_대상자다() {
            CareRequest request = request(SITUATION_ID, 24);
            RequestAssignment first = assignmentRepository.save(RequestAssignment.assign(request, MINJAE, 1, NOW.plusHours(12)));
            first.respond(AssignmentResponseStatus.UNAVAILABLE, NOW.minusHours(2));
            first.close(AssignmentCloseReason.TARGET_CHANGED, NOW.minusHours(1));
            entityManager.flush();  // 마감을 먼저 반영 (요청당 열린 지정 1건 제약)
            assignmentRepository.save(RequestAssignment.assign(request, SEOYEON, 2, NOW.plusHours(12)));
            CareRequest noAssignment = request(SITUATION_ID, 30);
            flushAndClear();

            List<RequestSummary> list = reader.getSituationRequests(SITUATION_ID, REQUESTER_ID, NOW);

            assertThat(list).extracting(RequestSummary::requestId, RequestSummary::targetUserId)
                    .containsExactly(tuple(request.getId(), SEOYEON), tuple(noAssignment.getId(), null));
        }

        @Test
        void 담당자_없음이어도_targetUserId는_마지막으로_부탁한_사람이다() {
            CareRequest request = request(SITUATION_ID, 24);
            unavailable(request, MINJAE);
            flushAndClear();

            RequestSummary summary = reader.getSituationRequests(SITUATION_ID, REQUESTER_ID, NOW).get(0);
            assertThat(summary.displayStatus()).isEqualTo(RequestDisplayStatus.NO_ASSIGNEE);
            assertThat(summary.targetUserId()).isEqualTo(MINJAE); // 담당자가 아님 → B는 displayStatus로 구분
        }

        @Test
        void 참여자는_지금_내가_대상자_담당자인_요청과_끝난_요청_중_내가_담당한_것만_본다() {
            CareRequest myWaiting = request(SITUATION_ID, 24);
            waiting(myWaiting, MINJAE);
            CareRequest myAccepted = request(SITUATION_ID, 25);
            accepted(myAccepted, MINJAE);
            CareRequest myCompleted = request(SITUATION_ID, 26);
            completed(myCompleted, MINJAE);
            waiting(request(SITUATION_ID, 27), SEOYEON);           // 다른 사람 요청 → 제외
            CareRequest passedOn = request(SITUATION_ID, 28);       // 나에서 다른 사람에게 넘어감 → 제외
            assignmentRepository.save(RequestAssignment.assign(passedOn, MINJAE, 1, NOW.plusHours(12)))
                    .close(AssignmentCloseReason.TARGET_CHANGED, NOW.minusHours(1));
            entityManager.flush();  // 마감을 먼저 반영 (요청당 열린 지정 1건 제약)
            assignmentRepository.save(RequestAssignment.assign(passedOn, SEOYEON, 2, NOW.plusHours(12)));
            CareRequest withdrawn = request(SITUATION_ID, 29);      // 내가 수락을 철회함 → 제외
            accepted(withdrawn, MINJAE).close(AssignmentCloseReason.ACCEPTANCE_WITHDRAWN, NOW.minusMinutes(30));
            withdrawn.revertToRequested();
            flushAndClear();

            assertThat(reader.getSituationRequests(SITUATION_ID, MINJAE, NOW)).extracting(RequestSummary::requestId)
                    .containsExactly(myWaiting.getId(), myAccepted.getId(), myCompleted.getId());
        }

        @Test
        void 관련_없는_사람은_아무것도_보지_못한다() {
            waiting(request(SITUATION_ID, 24), MINJAE);
            flushAndClear();

            assertThat(reader.getSituationRequests(SITUATION_ID, STRANGER_ID, NOW)).isEmpty();
        }
    }

    // === H-00 홈 ===

    @Nested
    class 홈 {

        @Test
        void 응답이_필요한_요청은_미응답_확인_후_답변이고_시작_전인_것이다() {
            CareRequest unanswered = request(SITUATION_ID, 48);
            waiting(unanswered, MINJAE);
            CareRequest checkLater = request(SITUATION_ID, 24);
            assignmentRepository.save(RequestAssignment.assign(checkLater, MINJAE, 1, NOW.plusHours(6)))
                    .respond(AssignmentResponseStatus.CHECK_LATER, NOW.minusHours(1));
            CareRequest deadlinePassed = request(SITUATION_ID, 30); // 기한이 지나도 시작 전이면 응답 가능
            deadlinePassed(deadlinePassed, MINJAE);
            unavailable(request(SITUATION_ID, 31), MINJAE);          // 어려움 → 제외
            accepted(request(SITUATION_ID, 32), MINJAE);             // 이미 수락 → 제외
            CareRequest started = careRequestRepository.save(CareRequest.createGeneral(SITUATION_ID, REQUESTER_ID,
                    HelpType.MEAL, "부탁 내용", "집", NOW.minusMinutes(10), NOW.plusHours(2), null));
            assignmentRepository.save(RequestAssignment.assign(started, MINJAE, 1, NOW.minusMinutes(10))); // 시작 지남 → 제외
            flushAndClear();

            HomeRequestOverview home = reader.getHomeOverview(MINJAE, NOW);

            // 기한이 빠른 순
            assertThat(home.responseNeeded()).extracting(ResponseNeededItem::requestId, ResponseNeededItem::type)
                    .containsExactly(
                            tuple(deadlinePassed.getId(), ResponseNeededType.RESPONSE),
                            tuple(checkLater.getId(), ResponseNeededType.RESPONSE),
                            tuple(unanswered.getId(), ResponseNeededType.RESPONSE));
            assertThat(home.responseNeededCount()).isEqualTo(3);
            ResponseNeededItem item = home.responseNeeded().get(1);
            assertThat(item.deadline()).isEqualTo(NOW.plusHours(6));
            assertThat(item.place()).isEqualTo("○○병원");
            assertThat(item.requesterId()).isEqualTo(REQUESTER_ID);
        }

        @Test
        void 재확인_대기_중인_수정안도_응답이_필요한_요청에_담고_기한이_지나면_뺀다() {
            CareRequest pending = request(SITUATION_ID, 48);
            RequestAssignment assignee = accepted(pending, MINJAE);
            RequestChangeProposal proposal = proposalRepository.save(RequestChangeProposal.create(pending, assignee,
                    Set.of(ChangeableField.PLACE), null, null, "△△병원", null, NOW.minusHours(1)));
            CareRequest expired = request(SITUATION_ID, 72);
            RequestAssignment expiredAssignee = accepted(expired, MINJAE);
            proposalRepository.save(RequestChangeProposal.create(expired, expiredAssignee,
                    Set.of(ChangeableField.PLACE), null, null, "△△병원", null, NOW.minusHours(25))); // 기한 1시간 전
            flushAndClear();

            HomeRequestOverview home = reader.getHomeOverview(MINJAE, NOW);

            assertThat(home.responseNeeded()).extracting(ResponseNeededItem::requestId, ResponseNeededItem::type)
                    .containsExactly(tuple(pending.getId(), ResponseNeededType.RECONFIRMATION));
            assertThat(home.responseNeeded().get(0).deadline()).isEqualTo(proposal.getResponseDeadline());
        }

        @Test
        void 내가_보낸_요청은_끝나지_않은_것만이고_담당자_없음_수를_센다() {
            CareRequest waiting = request(SITUATION_ID, 24);
            waiting(waiting, MINJAE);
            CareRequest noAssignee = request(SITUATION_ID, 25);
            unavailable(noAssignee, SEOYEON);
            CareRequest accepted = request(SITUATION_ID, 26);
            accepted(accepted, MINJAE);
            completed(request(SITUATION_ID, 27), MINJAE); // 끝남 → 제외
            flushAndClear();

            HomeRequestOverview home = reader.getHomeOverview(REQUESTER_ID, NOW);

            assertThat(home.sentRequests()).extracting(RequestSummary::requestId)
                    .containsExactly(waiting.getId(), noAssignee.getId(), accepted.getId());
            assertThat(home.noAssigneeCount()).isEqualTo(1);
            assertThat(home.responseNeeded()).isEmpty();
        }

        @Test
        void 다가오는_일정은_내가_부탁했거나_맡은_수락됨_진행_중_요청이고_빠른_순_2개까지다() {
            // 민재 기준: 민재가 맡은 요청 3개 + 민재가 보낸 요청 1개
            CareRequest mineToHelp1 = request(SITUATION_ID, 30);
            accepted(mineToHelp1, MINJAE);
            CareRequest mineToHelp2 = request(SITUATION_ID, 10);
            inProgress(mineToHelp2, MINJAE);
            CareRequest mineToHelp3 = request(SITUATION_ID, 50);
            accepted(mineToHelp3, MINJAE);
            CareRequest sentByMinjae = careRequestRepository.save(CareRequest.createGeneral(OTHER_SITUATION_ID, MINJAE,
                    HelpType.MEAL, "부탁 내용", "집", NOW.plusHours(20), NOW.plusHours(21), null));
            accepted(sentByMinjae, SEOYEON);
            flushAndClear();

            HomeRequestOverview home = reader.getHomeOverview(MINJAE, NOW);

            assertThat(home.upcomingCount()).isEqualTo(4); // 개수는 전체
            assertThat(home.upcoming()).extracting(UpcomingSchedule::requestId, UpcomingSchedule::assigneeId)
                    .containsExactly(tuple(mineToHelp2.getId(), MINJAE), tuple(sentByMinjae.getId(), SEOYEON));
        }
    }

    // === H-04 지난 상황, N-04 지난 상황 상세 ===

    @Nested
    class 지난_상황 {

        @Test
        void 지난_상황별_요청_수와_완료_수를_센다() {
            completed(request(SITUATION_ID, 1), MINJAE);
            completed(request(SITUATION_ID, 2), SEOYEON);
            canceledBeforeStart(request(SITUATION_ID, 3), MINJAE);
            expired(request(SITUATION_ID, 4), MINJAE);
            completed(request(OTHER_SITUATION_ID, 1), MINJAE);
            flushAndClear();

            Map<Long, PastSituationStats> stats =
                    reader.getPastSituationStats(List.of(SITUATION_ID, OTHER_SITUATION_ID, EMPTY_SITUATION_ID));

            assertThat(stats.get(SITUATION_ID)).isEqualTo(new PastSituationStats(4, 2)); // 취소·만료도 요청 수에 포함
            assertThat(stats.get(OTHER_SITUATION_ID)).isEqualTo(new PastSituationStats(1, 1));
            assertThat(stats.get(EMPTY_SITUATION_ID)).isEqualTo(PastSituationStats.empty());
            assertThat(reader.getPastSituationStats(List.of())).isEmpty();
        }

        @Test
        void 요청자는_끝난_요청을_모두_보고_기록상_담당자를_받는다() {
            CareRequest done = request(SITUATION_ID, 1);
            completed(done, MINJAE);
            CareRequest expired = request(SITUATION_ID, 2);
            expired(expired, SEOYEON);
            CareRequest canceledAfterAccept = request(SITUATION_ID, 3);
            canceledAfterAccept(canceledAfterAccept, SEOYEON);
            accepted(request(SITUATION_ID, 4), MINJAE); // 끝나지 않음 → 제외
            flushAndClear();

            List<PastRequestRecord> records = reader.getPastRequestRecords(SITUATION_ID, REQUESTER_ID);

            assertThat(records).extracting(PastRequestRecord::requestId, PastRequestRecord::assigneeId,
                            PastRequestRecord::status)
                    .containsExactly(
                            tuple(done.getId(), MINJAE, RequestDisplayStatus.COMPLETED),
                            tuple(expired.getId(), null, RequestDisplayStatus.EXPIRED),        // 담당자 없이 만료
                            tuple(canceledAfterAccept.getId(), SEOYEON, RequestDisplayStatus.CANCELED));
        }

        @Test
        void 참여자는_자기가_담당한_끝난_요청만_본다() {
            CareRequest mine = request(SITUATION_ID, 1);
            completed(mine, MINJAE);
            completed(request(SITUATION_ID, 2), SEOYEON);
            CareRequest declined = request(SITUATION_ID, 3);   // 민재가 거절하고 만료 → 담당자가 아님
            unavailable(declined, MINJAE);
            declined.expire(NOW);
            flushAndClear();

            assertThat(reader.getPastRequestRecords(SITUATION_ID, MINJAE)).extracting(PastRequestRecord::requestId)
                    .containsExactly(mine.getId());
            assertThat(reader.getPastRequestRecords(SITUATION_ID, STRANGER_ID)).isEmpty();
        }
    }

    // === 도움 기록 (F-22, B #64) ===

    @Nested
    class 도움_기록 {

        @Test
        void 받은_기록과_도와준_기록을_시작_최신순으로_나눠_돌려준다() {
            CareRequest received = request(SITUATION_ID, 10);   // 요청자가 받은 도움 (민재가 도움)
            completed(received, MINJAE);
            CareRequest receivedLater = request(SITUATION_ID, 20);
            completed(receivedLater, SEOYEON);
            CareRequest given = careRequestRepository.save(CareRequest.createGeneral(OTHER_SITUATION_ID, MINJAE,
                    HelpType.MEAL, "부탁 내용", "집", NOW.plusHours(30), NOW.plusHours(31), null));
            completed(given, REQUESTER_ID);                      // 요청자가 민재를 도움
            accepted(request(SITUATION_ID, 40), MINJAE);         // 끝나지 않음 → 제외
            flushAndClear();

            Page<HelpRecord> all = reader.getHelpRecords(REQUESTER_ID, HelpRecordFilter.ALL, null, PageRequest.of(0, 10));
            assertThat(all.getContent()).extracting(HelpRecord::requestId, HelpRecord::role, HelpRecord::assigneeId)
                    .containsExactly(
                            tuple(given.getId(), HelpRecordFilter.GIVEN, REQUESTER_ID),
                            tuple(receivedLater.getId(), HelpRecordFilter.RECEIVED, SEOYEON),
                            tuple(received.getId(), HelpRecordFilter.RECEIVED, MINJAE));
            assertThat(all.getContent().get(0).situationId()).isEqualTo(OTHER_SITUATION_ID);

            assertThat(all.getContent().get(0).status()).isEqualTo(RequestDisplayStatus.COMPLETED);

            assertThat(reader.getHelpRecords(REQUESTER_ID, HelpRecordFilter.RECEIVED, null, PageRequest.of(0, 10))
                    .getContent()).extracting(HelpRecord::requestId)
                    .containsExactly(receivedLater.getId(), received.getId());
            assertThat(reader.getHelpRecords(REQUESTER_ID, HelpRecordFilter.GIVEN, null, PageRequest.of(0, 10))
                    .getContent()).extracting(HelpRecord::requestId)
                    .containsExactly(given.getId());
        }

        @Test
        void 상대방으로_거르면_그_사람과의_기록만_남는다() {
            CareRequest withMinjae = request(SITUATION_ID, 10);
            completed(withMinjae, MINJAE);
            completed(request(SITUATION_ID, 20), SEOYEON);
            CareRequest givenToMinjae = careRequestRepository.save(CareRequest.createGeneral(OTHER_SITUATION_ID, MINJAE,
                    HelpType.MEAL, "부탁 내용", "집", NOW.plusHours(30), NOW.plusHours(31), null));
            completed(givenToMinjae, REQUESTER_ID);
            flushAndClear();

            assertThat(reader.getHelpRecords(REQUESTER_ID, HelpRecordFilter.ALL, MINJAE, PageRequest.of(0, 10))
                    .getContent()).extracting(HelpRecord::requestId)
                    .containsExactly(givenToMinjae.getId(), withMinjae.getId());
        }

        @Test
        void 수락을_철회한_사람은_도와준_기록에_남지_않는다() {
            CareRequest request = request(SITUATION_ID, 10);
            RequestAssignment first = accepted(request, MINJAE);
            first.close(AssignmentCloseReason.ACCEPTANCE_WITHDRAWN, NOW.minusHours(2));
            request.revertToRequested();
            entityManager.flush();  // 마감을 먼저 반영 (요청당 열린 지정 1건 제약)
            RequestAssignment second = assignmentRepository.save(RequestAssignment.assign(request, SEOYEON, 2, NOW.plusHours(5)));
            second.respond(AssignmentResponseStatus.AVAILABLE, NOW.minusHours(1));
            request.accept();
            request.start(NOW);
            request.complete(NOW);
            flushAndClear();

            assertThat(reader.getHelpRecords(MINJAE, HelpRecordFilter.GIVEN, null, PageRequest.of(0, 10))).isEmpty();
            assertThat(reader.getHelpRecords(SEOYEON, HelpRecordFilter.GIVEN, null, PageRequest.of(0, 10))
                    .getContent()).extracting(HelpRecord::assigneeId).containsExactly(SEOYEON);
        }

        @Test
        void 페이지로_나눠_돌려준다() {
            for (int i = 1; i <= 3; i++) {
                completed(request(SITUATION_ID, i), MINJAE);
            }
            flushAndClear();

            Page<HelpRecord> first = reader.getHelpRecords(REQUESTER_ID, HelpRecordFilter.ALL, null, PageRequest.of(0, 2));
            Page<HelpRecord> second = reader.getHelpRecords(REQUESTER_ID, HelpRecordFilter.ALL, null, PageRequest.of(1, 2));
            assertThat(first.getTotalElements()).isEqualTo(3);
            assertThat(first.getContent()).hasSize(2);
            assertThat(second.getContent()).hasSize(1);
        }
    }

    // === 도우미: 요청 상태 만들기 ===

    /** 요청자의 일반 요청. 지금부터 startHours 뒤 시작, 3시간 */
    private CareRequest request(Long situationId, int startHours) {
        return careRequestRepository.save(CareRequest.createGeneral(situationId, REQUESTER_ID, HelpType.HOSPITAL,
                "부탁 내용", "○○병원", NOW.plusHours(startHours), NOW.plusHours(startHours + 3), null));
    }

    /** 응답 대기: 미응답, 응답 기한 전 */
    private RequestAssignment waiting(CareRequest request, Long targetUserId) {
        return assignmentRepository.save(RequestAssignment.assign(request, targetUserId, 1,
                min(NOW.plusHours(12), request.getStartsAt())));
    }

    /** 담당자 없음: 어려움 응답 */
    private void unavailable(CareRequest request, Long targetUserId) {
        waiting(request, targetUserId).respond(AssignmentResponseStatus.UNAVAILABLE, NOW.minusMinutes(30));
    }

    /** 담당자 없음: 응답 기한 지남 (시작 전) */
    private void deadlinePassed(CareRequest request, Long targetUserId) {
        assignmentRepository.save(RequestAssignment.assign(request, targetUserId, 1, NOW.minusMinutes(1)));
    }

    /** 수락됨 (도움 가능 응답) */
    private RequestAssignment accepted(CareRequest request, Long targetUserId) {
        RequestAssignment assignment = waiting(request, targetUserId);
        assignment.respond(AssignmentResponseStatus.AVAILABLE, NOW.minusHours(1));
        request.accept();
        return assignment;
    }

    private void inProgress(CareRequest request, Long targetUserId) {
        accepted(request, targetUserId);
        request.start(NOW);
    }

    /** 완료 (완료는 지정을 마감하지 않음) */
    private void completed(CareRequest request, Long targetUserId) {
        inProgress(request, targetUserId);
        request.complete(NOW);
    }

    /** 수락 전에 요청자가 취소 (열린 지정은 요청 종료로 함께 마감) */
    private void canceledBeforeStart(CareRequest request, Long targetUserId) {
        waiting(request, targetUserId).close(AssignmentCloseReason.REQUEST_ENDED, NOW);
        request.cancel(CancelReason.REQUESTER_CANCELED, NOW);
    }

    /** 수락 후 시작 전에 요청자가 취소 → 기록상 담당자는 수락했던 사람 */
    private void canceledAfterAccept(CareRequest request, Long targetUserId) {
        accepted(request, targetUserId).close(AssignmentCloseReason.REQUEST_ENDED, NOW);
        request.cancel(CancelReason.REQUESTER_CANCELED, NOW);
    }

    /** 담당자 없이 시작 시각 도달 → 만료 */
    private void expired(CareRequest request, Long targetUserId) {
        waiting(request, targetUserId).close(AssignmentCloseReason.REQUEST_ENDED, NOW);
        request.expire(NOW);
    }

    private static LocalDateTime min(LocalDateTime a, LocalDateTime b) {
        return a.isBefore(b) ? a : b;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }}