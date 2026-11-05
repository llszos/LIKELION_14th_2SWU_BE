package com.gyeot.domain.carerequest.event;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.entity.RequestChangeProposal;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.ChangeableField;
import com.gyeot.domain.carerequest.enums.ProposalStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 수정 확정 (수락 전 직접 수정, 또는 수락 후 재확인 수락)
 * - D: informationIds가 있으면 현재 지정의 공개 항목 변경 반영
 *      (수락 전: PENDING 권한 생성·회수 / 재확인 수락: ACTIVE 권한 생성·회수, ERD 4.8)
 * - B(알림): 대상자·담당자에게 수정 알림
 *
 * - proposalId: 재확인 수락이면 제안 ID, 수락 전 직접 수정이면 null
 * - assignmentId·targetUserId: 현재 열린 지정. 수락 전 수정인데 열린 지정이 없으면(대상자가 빠진 상태) 둘 다 null
 * - informationIds: changedFields에 INFORMATION_IDS가 있을 때만 값이 있음 (변경 후 전체 목록, 모두 제외는 빈 목록)
 */
public record CareRequestChangeConfirmedEvent(
        Long requestId,
        Long requesterId,
        Long proposalId,
        Long assignmentId,
        Long targetUserId,
        Set<ChangeableField> changedFields,
        List<Long> informationIds,
        LocalDateTime occurredAt
) implements CareRequestEvent {

    public CareRequestChangeConfirmedEvent {
        Objects.requireNonNull(requestId, "requestId는 필수입니다.");
        Objects.requireNonNull(requesterId, "requesterId는 필수입니다.");
        Objects.requireNonNull(occurredAt, "occurredAt은 필수입니다.");
        if (changedFields == null || changedFields.isEmpty()) {
            throw new IllegalArgumentException("변경한 필드가 하나 이상 있어야 합니다.");
        }
        if ((assignmentId == null) != (targetUserId == null)) {
            throw new IllegalArgumentException("assignmentId와 targetUserId는 함께 있거나 함께 없어야 합니다.");
        }
        boolean changesInfo = changedFields.contains(ChangeableField.INFORMATION_IDS);
        if (changesInfo != (informationIds != null)) {
            throw new IllegalArgumentException(
                    "공개 항목은 변경할 때만 값이 있어야 하며, 모두 제외할 때는 빈 목록을 사용합니다.");
        }
        changedFields = Set.copyOf(changedFields);
        informationIds = informationIds == null ? null : List.copyOf(informationIds);
    }

    // 수락 전 직접 수정: careRequest.applyChanges() 뒤에 호출합니다. 열린 지정이 없으면 currentAssignment는 null입니다.
    public static CareRequestChangeConfirmedEvent ofDirectEdit(CareRequest careRequest,
                                                               RequestAssignment currentAssignment,
                                                               Set<ChangeableField> changedFields,
                                                               List<Long> informationIds,
                                                               LocalDateTime occurredAt) {
        if (careRequest.getStatus() != CareRequestStatus.REQUESTED) {
            throw new IllegalStateException("수락 전 요청만 직접 수정할 수 있습니다: " + careRequest.getStatus());
        }
        Long assignmentId = currentAssignment == null ? null : currentAssignment.getId();
        Long targetUserId = currentAssignment == null ? null : currentAssignment.getTargetUserId();
        return new CareRequestChangeConfirmedEvent(careRequest.getId(), careRequest.getRequesterId(), null,
                assignmentId, targetUserId, changedFields, informationIds, occurredAt);
    }

    // 재확인 수락: proposal.accept()와 careRequest.applyChanges() 뒤에 호출합니다.
    public static CareRequestChangeConfirmedEvent ofProposal(RequestChangeProposal proposal, LocalDateTime occurredAt) {
        if (proposal.getStatus() != ProposalStatus.ACCEPTED) {
            throw new IllegalStateException("수락된 제안이 아닙니다: " + proposal.getStatus());
        }
        return new CareRequestChangeConfirmedEvent(proposal.getCareRequest().getId(),
                proposal.getCareRequest().getRequesterId(), proposal.getId(),
                proposal.getAssignment().getId(), proposal.getAssignment().getTargetUserId(),
                proposal.getChangedFields(), proposal.getProposedInformationIds(), occurredAt);
    }
}