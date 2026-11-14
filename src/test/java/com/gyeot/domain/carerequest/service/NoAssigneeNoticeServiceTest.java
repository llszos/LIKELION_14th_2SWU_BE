package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.carerequest.dto.response.NoAssigneeNoticesResponse.Item;
import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.NoAssigneeReason;
import com.gyeot.domain.carerequest.exception.CareRequestErrorCode;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
import com.gyeot.global.config.JpaAuditingConfig;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import com.gyeot.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 담당자 없음 안내 목록, 닫기를 실제 MySQL로 확인합니다.
 * 이슈 체크리스트의 "사유 변경 시 재표시, 수락 후 초기화"를 중심으로 확인합니다.
 * 현재 시각은 NOW로 고정합니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAuditingConfig.class,
        SafetyInformationCipher.class, // 안심카드 엔티티의 암호화 Converter가 필요로 함
        CareRequestFinder.class,
        NoAssigneeNoticeService.class,
        NoAssigneeNoticeServiceTest.FixedClockConfig.class // @Nested 안쪽 클래스는 바깥의 설정을 자동으로 못 찾아서 직접 등록
})
@ActiveProfiles("test")
@Testcontainers
public class NoAssigneeNoticeServiceTest {
    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 9, 0);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            ZoneId seoul = ZoneId.of("Asia/Seoul");
            return Clock.fixed(NOW.atZone(seoul).toInstant(), seoul);
        }
    }

    private static final Long REQUESTER = 10L;
    private static final Long MINJAE = 20L;
    private static final Long SEOYEON = 30L;
    private static final Long STRANGER = 99L;

    @Autowired private NoAssigneeNoticeService service;
    @Autowired private CareRequestRepository careRequestRepository;
    @Autowired private RequestAssignmentRepository assignmentRepository;
    @Autowired private EntityManager entityManager;

    // === 목록 ===

    @Nested
    class 목록 {

        @Test
        void 담당자_없음인_요청만_사유와_함께_시작_순으로_보여_준다() {
            CareRequest unavailable = request(NOW.plusDays(2));
            assign(unavailable, MINJAE, 1, NOW.plusHours(12)).respond(AssignmentResponseStatus.UNAVAILABLE, NOW);
            CareRequest deadlinePassed = request(NOW.plusDays(1));
            assign(deadlinePassed, SEOYEON, 1, NOW.minusMinutes(1));        // 응답 기한 지남
            CareRequest waiting = request(NOW.plusDays(1));
            assign(waiting, MINJAE, 1, NOW.plusHours(12));                   // 응답 대기 → 제외
            CareRequest accepted = request(NOW.plusDays(1));
            accept(accepted, assign(accepted, MINJAE, 1, NOW.plusHours(12))); // 수락됨 → 제외
            flush();

            assertThat(notices())
                    .extracting(Item::requestId, Item::reason, i -> i.target().userId(), Item::place)
                    .containsExactly(
                            tuple(deadlinePassed.getId(), NoAssigneeReason.DEADLINE_PASSED, SEOYEON, "○○병원"),
                            tuple(unavailable.getId(), NoAssigneeReason.UNAVAILABLE, MINJAE, "○○병원"));
        }

        @Test
        void 수락_철회로_담당자가_없어지면_보여_준다() {
            CareRequest request = request(NOW.plusDays(1));
            RequestAssignment assignment = assign(request, MINJAE, 1, NOW.plusHours(12));
            accept(request, assignment);
            assignment.close(AssignmentCloseReason.ACCEPTANCE_WITHDRAWN, NOW);
            request.revertToRequested();
            flush();

            assertThat(notices()).singleElement()
                    .extracting(Item::reason).isEqualTo(NoAssigneeReason.ACCEPTANCE_WITHDRAWN);
        }

        @Test
        void 시작_시각이_지난_요청과_다른_사람의_요청은_보여_주지_않는다() {
            CareRequest started = request(NOW.minusMinutes(1)); // 시작이 지남 (곧 만료됨)
            assign(started, MINJAE, 1, NOW.minusHours(2)).respond(AssignmentResponseStatus.UNAVAILABLE, NOW.minusHours(3));
            CareRequest others = careRequestRepository.save(CareRequest.createGeneral(1L, STRANGER, HelpType.MEAL,
                    "식사", "집", NOW.plusDays(1), NOW.plusDays(1).plusHours(1), null));
            assign(others, MINJAE, 1, NOW.plusHours(12)).respond(AssignmentResponseStatus.UNAVAILABLE, NOW);
            flush();

            assertThat(notices()).isEmpty();
        }

        @Test
        void 다른_사람에게_다시_부탁하면_가장_최근_지정으로_판단한다() {
            CareRequest request = request(NOW.plusDays(1));
            RequestAssignment first = assign(request, MINJAE, 1, NOW.plusHours(12));
            first.respond(AssignmentResponseStatus.UNAVAILABLE, NOW);
            first.close(AssignmentCloseReason.TARGET_CHANGED, NOW);
            assign(request, SEOYEON, 2, NOW.plusHours(12)); // 서연에게 다시 부탁 → 응답 대기
            flush();

            assertThat(notices()).isEmpty(); // 지금 대상(서연)이 응답 대기라 담당자 없음이 아님
        }
    }

    // === 닫기 ===

    @Nested
    class 닫기 {

        @Test
        void 닫으면_같은_사유로는_다시_보여_주지_않는다() {
            CareRequest request = unavailableRequest();

            service.dismiss(request.getId(), REQUESTER, NoAssigneeReason.UNAVAILABLE);

            assertThat(notices()).isEmpty();
        }

        @Test
        void 사유가_바뀌면_새_사유로_다시_보여_준다() {
            // 기한 경과로 닫은 뒤 같은 대상이 '어려움'으로 답함 (F-14 예시)
            CareRequest request = request(NOW.plusDays(1));
            RequestAssignment assignment = assign(request, MINJAE, 1, NOW.minusMinutes(1));
            service.dismiss(request.getId(), REQUESTER, NoAssigneeReason.DEADLINE_PASSED);
            assertThat(notices()).isEmpty();

            assignment.respond(AssignmentResponseStatus.UNAVAILABLE, NOW);
            flush();

            assertThat(notices()).singleElement()
                    .extracting(Item::reason).isEqualTo(NoAssigneeReason.UNAVAILABLE);
        }

        @Test
        void 보여_준_사유를_그대로_기록하므로_보는_사이_사유가_바뀌면_한_번_더_뜬다() {
            // 화면에는 '기한 경과'를 보여 줬는데, 그 사이 대상이 '어려움'으로 답한 상태에서 닫음
            CareRequest request = request(NOW.plusDays(1));
            assign(request, MINJAE, 1, NOW.minusMinutes(1)).respond(AssignmentResponseStatus.UNAVAILABLE, NOW);

            service.dismiss(request.getId(), REQUESTER, NoAssigneeReason.DEADLINE_PASSED); // 서버가 다시 계산하지 않음

            assertThat(notices()).singleElement()
                    .extracting(Item::reason).isEqualTo(NoAssigneeReason.UNAVAILABLE);
        }

        @Test
        void 수락하면_닫은_기록이_초기화되어_다음_담당자_없음_때_다시_보여_준다() {
            CareRequest request = request(NOW.plusDays(1));
            RequestAssignment assignment = assign(request, MINJAE, 1, NOW.plusHours(12));
            assignment.respond(AssignmentResponseStatus.UNAVAILABLE, NOW);            // 어려움 → 모달
            service.dismiss(request.getId(), REQUESTER, NoAssigneeReason.UNAVAILABLE); // 요청자가 닫음

            accept(request, assignment);                       // 어려움 → 수락: 닫은 기록 초기화
            assertThat(assignment.getNoticeReason()).isNull();

            assignment.close(AssignmentCloseReason.ACCEPTANCE_WITHDRAWN, NOW); // 수락 철회 → 다시 담당자 없음
            request.revertToRequested();
            flush();

            assertThat(notices()).singleElement()
                    .extracting(Item::reason).isEqualTo(NoAssigneeReason.ACCEPTANCE_WITHDRAWN);
        }

        @Test
        void 보는_사이_담당자_없음이_아니게_됐으면_기록하지_않고_성공한다() {
            CareRequest request = request(NOW.plusDays(1));
            RequestAssignment assignment = assign(request, MINJAE, 1, NOW.plusHours(12));
            accept(request, assignment); // 모달을 보는 사이 대상이 수락함

            service.dismiss(request.getId(), REQUESTER, NoAssigneeReason.UNAVAILABLE); // 예외 없이 끝남

            assertThat(assignment.getNoticeReason()).isNull();
            assertThat(assignment.getNoticeClosedAt()).isNull();
        }

        @Test
        void 대상자가_닫으면_403이다() {
            CareRequest request = unavailableRequest();

            // 대상자는 요청을 볼 수 있는 사람이라 404가 아니라 403 (API 공통 규칙 2)
            assertFailsWith(request.getId(), MINJAE, CommonErrorCode.FORBIDDEN);
        }

        @Test
        void 담당자가_닫으면_403이다() {
            CareRequest request = request(NOW.plusDays(1));
            accept(request, assign(request, MINJAE, 1, NOW.plusHours(12)));
            flush();

            assertFailsWith(request.getId(), MINJAE, CommonErrorCode.FORBIDDEN);
        }

        @Test
        void 관련_없는_사람이_닫으면_404다() {
            CareRequest request = unavailableRequest();

            assertFailsWith(request.getId(), STRANGER, CareRequestErrorCode.REQUEST_NOT_FOUND);
        }

        private CareRequest unavailableRequest() {
            CareRequest request = request(NOW.plusDays(1));
            assign(request, MINJAE, 1, NOW.plusHours(12)).respond(AssignmentResponseStatus.UNAVAILABLE, NOW);
            flush();
            return request;
        }

        private void assertFailsWith(Long requestId, Long userId, ErrorCode expected) {
            assertThatThrownBy(() -> service.dismiss(requestId, userId, NoAssigneeReason.UNAVAILABLE))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(expected);
        }
    }

    // === 준비 코드 ===

    private List<Item> notices() {
        flush();
        return service.getNotices(REQUESTER).items();
    }

    private CareRequest request(LocalDateTime startsAt) {
        return careRequestRepository.save(CareRequest.createGeneral(1L, REQUESTER, HelpType.HOSPITAL,
                "병원 동행 부탁드려요", "○○병원", startsAt, startsAt.plusHours(3), null));
    }

    private RequestAssignment assign(CareRequest request, Long targetUserId, int order, LocalDateTime deadline) {
        return assignmentRepository.save(RequestAssignment.assign(request, targetUserId, order, deadline));
    }

    private void accept(CareRequest request, RequestAssignment assignment) {
        assignment.respond(AssignmentResponseStatus.AVAILABLE, NOW);
        request.accept();
    }

    private void flush() {
        entityManager.flush();
    }
}
