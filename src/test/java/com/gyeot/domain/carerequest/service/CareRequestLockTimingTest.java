package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.exception.CareRequestErrorCode;
import com.gyeot.domain.carerequest.repository.RequestChangeEventRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeProposalRepository;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.ErrorCode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 현재 시각을 잠금을 얻은 뒤에 구하는지 확인합니다. (#70 리뷰 반영)
 *
 * 잠금을 기다리는 동안 요청 시작 시각이 지났는데, 잠금 전에 구한 시각으로 검증하면
 * "아직 시작 전"으로 판단해 시작 후에도 수락, 철회가 통과하는 문제가 발생합니다.
 *
 * Finder(잠금 조회)를 Mock으로 바꾸고, 잠금을 주는 순간에 시계를 앞으로 돌립니다. → 잠금 대기 중에 시간이 흐른 것과 같은 상황
 * 서비스가 그 뒤의 시각으로 검증해야 통과하는 방식으로 테스트를 구현합니다.
 */
public class CareRequestLockTimingTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime STARTS_AT = LocalDateTime.of(2026, 11, 10, 14, 0);
    private static final LocalDateTime BEFORE_LOCK = STARTS_AT.minusMinutes(1); // 요청이 들어온 시각 (시작 1분 전)
    private static final LocalDateTime AFTER_LOCK = STARTS_AT.plusMinutes(1);   // 잠금을 얻은 시각 (시작 1분 후)
    private static final Long REQUESTER_ID = 10L;
    private static final Long TARGET_ID = 20L;
    private static final Long REQUEST_ID = 1L;

    private MutableClock clock;
    private CareRequestFinder finder;
    private ApplicationEventPublisher publisher;
    private CareRequestResponseService service;
    private CareRequest request;
    private RequestAssignment assignment;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(BEFORE_LOCK);
        finder = mock(CareRequestFinder.class);
        publisher = mock(ApplicationEventPublisher.class);
        service = new CareRequestResponseService(finder, mock(RequestChangeProposalRepository.class),
                mock(RequestChangeEventRepository.class), publisher, clock);

        request = CareRequest.createGeneral(100L, REQUESTER_ID, HelpType.HOSPITAL, "병원 동행", "○○병원",
                STARTS_AT, STARTS_AT.plusHours(3), null);
        assignment = RequestAssignment.assign(request, TARGET_ID, 1, STARTS_AT.minusHours(2));
        // DB에 저장하지 않아 ID가 없으므로 직접 넣어 줌 (이벤트는 ID가 필수)
        ReflectionTestUtils.setField(request, "id", REQUEST_ID);
        ReflectionTestUtils.setField(assignment, "id", 5L);
    }

    @Test
    void 잠금을_기다리는_사이_시작_시각이_지나면_수락할_수_없다() {
        givenLockGrantedAfterStart();

        assertError(() -> service.respond(REQUEST_ID, TARGET_ID, AssignmentResponseStatus.AVAILABLE),
                CareRequestErrorCode.REQUEST_ALREADY_STARTED);
        assertThat(assignment.getResponseStatus()).isNull(); // 아무것도 바뀌지 않음
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void 잠금을_기다리는_사이_시작_시각이_지나면_수락을_철회할_수_없다() {
        assignment.respond(AssignmentResponseStatus.AVAILABLE, BEFORE_LOCK.minusHours(3));
        request.accept();
        givenLockGrantedAfterStart();

        assertError(() -> service.withdraw(REQUEST_ID, TARGET_ID), CareRequestErrorCode.REQUEST_NOT_WITHDRAWABLE);
        assertThat(assignment.isCurrentAssignee()).isTrue(); // 담당자 그대로
    }

    @Test
    void 기록되는_수락_시각도_잠금을_얻은_뒤의_시각이다() {
        // 잠금을 얻은 뒤에도 아직 시작 전인 경우: 수락은 성공하고, 기록되는 시각은 잠금 이후 시각
        LocalDateTime lockGranted = STARTS_AT.minusSeconds(10);
        when(finder.getAccessibleRequestForUpdate(REQUEST_ID, TARGET_ID)).thenAnswer(invocation -> {
            clock.set(lockGranted);
            return RequestAccess.ofAssignee(request, assignment);
        });

        service.respond(REQUEST_ID, TARGET_ID, AssignmentResponseStatus.AVAILABLE);

        assertThat(assignment.getAcceptedAt()).isEqualTo(lockGranted);
    }

    /** 잠금 조회가 호출되는 순간(잠금을 얻을 때까지 기다린 뒤) 시계를 시작 시각 이후로 돌립니다. */
    private void givenLockGrantedAfterStart() {
        when(finder.getAccessibleRequestForUpdate(REQUEST_ID, TARGET_ID)).thenAnswer(invocation -> {
            clock.set(AFTER_LOCK);
            return RequestAccess.ofAssignee(request, assignment);
        });
    }

    private void assertError(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }

    /** 테스트에서 시각을 바꿀 수 있는 시계 (Clock.fixed는 한 번 정하면 못 바꿈) */
    private static class MutableClock extends Clock {
        private Instant instant;

        MutableClock(LocalDateTime initial) {
            set(initial);
        }

        void set(LocalDateTime time) {
            this.instant = time.atZone(SEOUL).toInstant();
        }

        @Override
        public ZoneId getZone() {
            return SEOUL;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
