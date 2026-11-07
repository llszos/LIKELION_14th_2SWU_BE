package com.gyeot.domain.carerequest.event;

import com.gyeot.domain.carerequest.entity.RequestChangeProposal;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 수정 제안 (수락 후 수정 → 담당자 재확인 요청)
 * - D: 처리 없음 (재확인 전에는 기존 권한 유지, ERD 4.8)
 * - B(알림): 담당자에게 재확인 요청 알림
 */
public record CareRequestChangeProposedEvent(
        Long requestId,
        Long requesterId,
        Long proposalId,
        Long assignmentId,
        Long assigneeId,
        LocalDateTime occurredAt
) implements CareRequestEvent {

    public CareRequestChangeProposedEvent {
        Objects.requireNonNull(requestId, "requestId는 필수입니다.");
        Objects.requireNonNull(requesterId, "requesterId는 필수입니다.");
        Objects.requireNonNull(proposalId, "proposalId는 필수입니다.");
        Objects.requireNonNull(assignmentId, "assignmentId는 필수입니다.");
        Objects.requireNonNull(assigneeId, "assigneeId는 필수입니다.");
        Objects.requireNonNull(occurredAt, "occurredAt은 필수입니다.");
    }

    // RequestChangeProposal.create()로 만들고 save()한 뒤에 호출합니다.
    public static CareRequestChangeProposedEvent of(RequestChangeProposal proposal, LocalDateTime occurredAt) {
        if (!proposal.isPending()) {
            throw new IllegalStateException("대기 중인 제안이 아닙니다: " + proposal.getStatus());
        }
        return new CareRequestChangeProposedEvent(proposal.getCareRequest().getId(),
                proposal.getCareRequest().getRequesterId(), proposal.getId(),
                proposal.getAssignment().getId(), proposal.getAssignment().getTargetUserId(), occurredAt);
    }
}