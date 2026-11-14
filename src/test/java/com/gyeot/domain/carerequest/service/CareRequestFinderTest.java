package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.exception.CareRequestErrorCode;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.global.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** DB 없이 Repository를 Mock으로 바꿔 역할 판단과 404 규칙만 확인합니다. */
public class CareRequestFinderTest {
    private static final Long REQUEST_ID = 1L;
    private static final Long REQUESTER_ID = 10L;
    private static final Long TARGET_ID = 20L;
    private static final Long STRANGER_ID = 30L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 9, 0);

    private CareRequestRepository careRequestRepository;
    private RequestAssignmentRepository assignmentRepository;
    private CareRequestFinder finder;
    private CareRequest request;

    @BeforeEach
    void setUp() {
        careRequestRepository = mock(CareRequestRepository.class);
        assignmentRepository = mock(RequestAssignmentRepository.class);
        finder = new CareRequestFinder(careRequestRepository, assignmentRepository);

        request = CareRequest.createGeneral(100L, REQUESTER_ID, HelpType.HOSPITAL, "병원 동행", "○○병원",
                NOW.plusDays(1), NOW.plusDays(1).plusHours(3), null);
        when(careRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
    }

    @Test
    void 요청자는_REQUESTER이고_내_지정이_없다() {
        RequestAccess access = finder.getAccessibleRequest(REQUEST_ID, REQUESTER_ID);

        assertThat(access.role()).isEqualTo(RequestViewerRole.REQUESTER);
        assertThat(access.myAssignment()).isNull();
    }

    @Test
    void 수락하지_않은_대상자는_TARGET이다() {
        RequestAssignment assignment = RequestAssignment.assign(request, TARGET_ID, 1, NOW.plusHours(24));
        givenAssignment(assignment);

        RequestAccess access = finder.getAccessibleRequest(REQUEST_ID, TARGET_ID);

        assertThat(access.role()).isEqualTo(RequestViewerRole.TARGET);
        assertThat(access.myAssignment()).isSameAs(assignment);
    }

    @Test
    void 지정이_마감된_과거_대상자도_볼_수_있다() {
        RequestAssignment assignment = RequestAssignment.assign(request, TARGET_ID, 1, NOW.plusHours(24));
        assignment.respond(AssignmentResponseStatus.UNAVAILABLE, NOW);
        assignment.close(AssignmentCloseReason.TARGET_CHANGED, NOW);
        givenAssignment(assignment);

        RequestAccess access = finder.getAccessibleRequest(REQUEST_ID, TARGET_ID);

        assertThat(access.role()).isEqualTo(RequestViewerRole.TARGET);
        assertThat(access.myAssignment().isOpen()).isFalse();
    }

    @Test
    void 수락한_사람은_ASSIGNEE다() {
        RequestAssignment assignment = RequestAssignment.assign(request, TARGET_ID, 1, NOW.plusHours(24));
        assignment.respond(AssignmentResponseStatus.AVAILABLE, NOW);
        givenAssignment(assignment);

        RequestAccess access = finder.getAccessibleRequest(REQUEST_ID, TARGET_ID);

        assertThat(access.role()).isEqualTo(RequestViewerRole.ASSIGNEE);
    }

    @Test
    void 관련_없는_사람은_404다() {
        when(assignmentRepository.findByCareRequestIdAndTargetUserId(REQUEST_ID, STRANGER_ID))
                .thenReturn(Optional.empty());

        assertNotFound(() -> finder.getAccessibleRequest(REQUEST_ID, STRANGER_ID));
    }

    @Test
    void 없는_요청은_404다() {
        when(careRequestRepository.findById(999L)).thenReturn(Optional.empty());

        assertNotFound(() -> finder.getAccessibleRequest(999L, REQUESTER_ID));
    }

    private void givenAssignment(RequestAssignment assignment) {
        when(assignmentRepository.findByCareRequestIdAndTargetUserId(REQUEST_ID, TARGET_ID))
                .thenReturn(Optional.of(assignment));
    }

    private void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CareRequestErrorCode.REQUEST_NOT_FOUND);
    }



}
