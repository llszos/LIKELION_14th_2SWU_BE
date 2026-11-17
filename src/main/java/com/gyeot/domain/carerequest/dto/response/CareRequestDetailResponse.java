package com.gyeot.domain.carerequest.dto.response;

import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import com.gyeot.domain.carerequest.enums.CancelReason;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.ChangeableField;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.InvalidationReason;
import com.gyeot.domain.carerequest.enums.NoAssigneeReason;
import com.gyeot.domain.carerequest.enums.ProposalStatus;
import com.gyeot.domain.carerequest.enums.RequestType;
import com.gyeot.domain.carerequest.query.RequestDisplayStatus;
import com.gyeot.domain.carerequest.service.RequestAction;
import com.gyeot.domain.carerequest.service.RequestViewerRole;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 도움 요청 상세 응답 (GET /api/care-requests/{requestId}, F-08)
 * 보는 사람(viewerRole)에 따라 채워지는 필드가 다릅니다. 해당하지 않는 필드는 null입니다.
 * - 요청자만: currentAssignment, noAssigneeReason, assignments, responseDeadlineHours
 * - 대상자·담당자만: myAssignment
 * - 요청자·(그 수정안의) 담당자만: latestProposal
 */
public record CareRequestDetailResponse(
        Long requestId,
        SituationView situation,              // 급한 요청이거나 상황을 찾을 수 없으면 null
        RequestType requestType,
        HelpType helpType,
        String description,                   // 입력하지 않았으면 null
        String place,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        CareRequestStatus status,
        RequestDisplayStatus displayStatus,
        CancelReason cancelReason,            // 취소가 아니면 null
        RequestViewerRole viewerRole,
        UserView requester,
        CurrentAssignmentView currentAssignment,
        NoAssigneeReason noAssigneeReason,
        List<AssignmentView> assignments,
        Integer responseDeadlineHours,
        MyAssignmentView myAssignment,
        ProposalView latestProposal,
        List<RequestAction> actions
) {

    /** 요청이 속한 상황 (status: ACTIVE / ENDED) */
    public record SituationView(Long situationId, LocalDate startDate, LocalDate endDate, String status) {
    }

    /** 사용자 (탈퇴했으면 name = "탈퇴한 사용자") */
    public record UserView(Long userId, String name) {
    }

    /** 대상자 (isGuardian: 요청자와 서로의 보호자면 true, 나의 사람이면 false) */
    public record TargetView(Long userId, String name, boolean isGuardian) {
    }

    /** 이 요청의 가장 최근 지정 (요청자 뷰 "To. 이름") */
    public record CurrentAssignmentView(
            Long assignmentId,
            TargetView target,
            AssignmentResponseStatus responseStatus,
            LocalDateTime responseDeadline,
            LocalDateTime acceptedAt,
            boolean closed,
            AssignmentCloseReason closeReason
    ) {
    }

    /** 부탁 순서 한 줄 */
    public record AssignmentView(
            int order,
            TargetView target,
            AssignmentResponseStatus responseStatus,
            AssignmentCloseReason closeReason
    ) {
    }

    /** 내 지정 (대상자·담당자 뷰) */
    public record MyAssignmentView(
            AssignmentResponseStatus responseStatus,
            LocalDateTime responseDeadline,
            boolean closed,
            AssignmentCloseReason closeReason
    ) {
    }

    /** 가장 최근 수정안 */
    public record ProposalView(
            Long proposalId,
            ProposalStatus status,
            InvalidationReason invalidationReason, // 무효가 아니면 null
            Set<ChangeableField> changedFields,
            ProposalValues before,
            ProposalValues after,
            LocalDateTime responseDeadline
    ) {
    }

    /** 수정 전·후 값 */
    public record ProposalValues(
            LocalDateTime startsAt,
            LocalDateTime endsAt,
            String place,
            List<InformationView> informations
    ) {
    }

    /** 공개 항목 (항목 내용은 주지 않고 유형 이름만) */
    public record InformationView(Long informationId, String name) {
    }
}
