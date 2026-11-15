package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.carerequest.dto.response.ProposalAcceptResponse;
import com.gyeot.domain.carerequest.dto.response.ProposalRejectResponse;
import com.gyeot.domain.carerequest.dto.response.ProposalWithdrawResponse;
import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestChangeEvent;
import com.gyeot.domain.carerequest.entity.RequestChangeProposal;
import com.gyeot.domain.carerequest.enums.CancelReason;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.InvalidationReason;
import com.gyeot.domain.carerequest.event.CareRequestChangeConfirmedEvent;
import com.gyeot.domain.carerequest.repository.RequestChangeEventRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeProposalRepository;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Clock;
import java.util.Objects;

import static com.gyeot.domain.carerequest.exception.CareRequestErrorCode.*;

/**
 * 수정안 철회(요청자), 재확인 수락·거절(담당자)을 처리합니다. (#73, F-29)
 *
 * 처리 순서는 세 API가 같습니다.
 * 1. 요청 행을 잠그고 관련자 확인 (관련 없으면 Finder가 404)
 * 2. 역할 확인 (철회는 요청자, 수락·거절은 그 수정안의 담당자 → 아니면 403)
 * 3. 수정안 조회 (없거나 이 요청의 수정안이 아니면 404 REQUEST_PROPOSAL_NOT_FOUND)
 *    요청 행을 잠근 뒤에 읽어서, 동시에 들어온 재수정·철회·스케줄러 처리 결과를 최신 상태로 봅니다.
 * 4. 처리할 수 있는지 확인 (이미 처리·무효 409 REQUEST_PROPOSAL_NOT_PENDING, 기한 지남·시작됨 409 REQUEST_PROPOSAL_EXPIRED)
 * 5. 상태 변경 → 이력 → 이벤트
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ChangeProposalService {

    private final CareRequestFinder careRequestFinder;
    private final CareRequestLifecycle lifecycle;
    private final RequestChangeProposalRepository proposalRepository;
    private final RequestChangeEventRepository eventRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    // === 수정안 철회 (POST .../change-proposals/{proposalId}/withdraw) ===

    /**
     * 요청자가 재확인 대기 중인 수정안을 철회합니다. 기존 요청 내용, 수락, 공개 권한은 그대로입니다.
     * 다른 영역에 알릴 일이 없어 이벤트를 발행하지 않습니다.
     */
    public ProposalWithdrawResponse withdraw(Long requestId, Long proposalId, Long userId) {
        RequestAccess access = careRequestFinder.getAccessibleRequestForUpdate(requestId, userId);
        LocalDateTime now = LocalDateTime.now(clock);
        if (!access.isRequester()) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN); // 요청자만 철회 가능
        }
        RequestChangeProposal proposal = getProposalOf(requestId, proposalId);
        if (!proposal.isPending()) {
            throw new BusinessException(REQUEST_PROPOSAL_NOT_PENDING); // 담당자가 먼저 응답했거나 이미 무효
        }

        proposal.invalidate();
        eventRepository.save(RequestChangeEvent.reconfirmationInvalidated(
                proposal, InvalidationReason.REQUESTER_WITHDRAWN, userId, now));
        return new ProposalWithdrawResponse(requestId, proposalId, proposal.getStatus());
    }

    // === 재확인 수락 (POST .../change-proposals/{proposalId}/accept) ===

    /**
     * 담당자가 수정 내용을 확인합니다. 수정안이 요청에 반영되고, 공개 항목이 바뀌었으면 D가 권한을 바꿉니다.
     * 수락 시점에는 1시간 규칙을 다시 검사하지 않습니다. (수정 시점에 검사, 기획 답변)
     */
    public ProposalAcceptResponse accept(Long requestId, Long proposalId, Long userId) {
        RequestAccess access = careRequestFinder.getAccessibleRequestForUpdate(requestId, userId);
        LocalDateTime now = LocalDateTime.now(clock);
        RequestChangeProposal proposal = getRespondableProposal(access, requestId, proposalId, userId, now);
        CareRequest request = access.request();

        LocalDateTime previousStartsAt = request.getStartsAt(); // 이력에 남길 변경 전 일정
        LocalDateTime previousEndsAt = request.getEndsAt();

        proposal.accept(now);
        request.applyProposal(proposal);

        eventRepository.save(RequestChangeEvent.reconfirmationAccepted(
                proposal, previousStartsAt, previousEndsAt, userId, now));
        // D: 공개 항목이 바뀌었으면 담당자의 ACTIVE 권한 반영 / B: 요청자에게 확인 알림
        eventPublisher.publishEvent(CareRequestChangeConfirmedEvent.ofProposal(proposal, now));
        return new ProposalAcceptResponse(requestId, proposalId, proposal.getStatus(), request.getStatus());
    }

    // === 재확인 거절 (POST .../change-proposals/{proposalId}/reject) ===

    /**
     * 담당자가 수정 내용을 거절합니다. 요청은 취소(RECONFIRMATION_REJECTED)되고 공개 권한은 회수됩니다. (F-29)
     * 취소 처리(지정 마감, 이력, 취소 이벤트)는 사용자 취소와 같은 CareRequestLifecycle.cancel을 씁니다.
     */
    public ProposalRejectResponse reject(Long requestId, Long proposalId, Long userId) {
        RequestAccess access = careRequestFinder.getAccessibleRequestForUpdate(requestId, userId);
        LocalDateTime now = LocalDateTime.now(clock);
        RequestChangeProposal proposal = getRespondableProposal(access, requestId, proposalId, userId, now);
        CareRequest request = access.request();

        proposal.reject(now);
        eventRepository.save(RequestChangeEvent.reconfirmationRejected(proposal, userId, now));
        // 거절한 수정안은 이미 REJECTED라 Lifecycle의 대기 수정안 무효화 대상이 아님
        lifecycle.cancel(request, CancelReason.RECONFIRMATION_REJECTED, userId, now);

        return new ProposalRejectResponse(requestId, proposalId, proposal.getStatus(),
                request.getStatus(), request.getCancelReason());
    }

    // === 내부 처리 ===

    /** 수정안을 찾습니다. 없거나 이 요청의 수정안이 아니면 404 */
    private RequestChangeProposal getProposalOf(Long requestId, Long proposalId) {
        return proposalRepository.findById(proposalId)
                .filter(proposal -> Objects.equals(proposal.getCareRequest().getId(), requestId))
                .orElseThrow(() -> new BusinessException(REQUEST_PROPOSAL_NOT_FOUND));
    }

    /**
     * 담당자가 수락·거절할 수 있는 수정안인지 확인합니다. 위에서부터 순서대로 확인해, 가장 먼저 걸린 이유로 응답합니다.
     * 엔티티의 accept()·reject()가 확인하는 조건(requireStillValid)을 사용자에게 보여 줄 에러 코드로 바꿔 두었습니다.
     */
    private RequestChangeProposal getRespondableProposal(RequestAccess access, Long requestId, Long proposalId,
                                                         Long userId, LocalDateTime now) {
        if (access.isRequester()) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN); // 요청자는 재확인할 수 없음
        }
        RequestChangeProposal proposal = getProposalOf(requestId, proposalId);
        if (!Objects.equals(proposal.getAssignment().getTargetUserId(), userId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN); // 그 수정안의 담당자가 아님 (이전 대상 등)
        }
        if (!proposal.isPending()) {
            throw new BusinessException(REQUEST_PROPOSAL_NOT_PENDING); // 이미 처리됐거나 재수정·철회·마감으로 무효
        }
        CareRequest request = access.request();
        boolean started = request.getStatus() != CareRequestStatus.ACCEPTED // 진행 중 등 (보통은 이미 무효)
                || (!request.isUrgent() && !now.isBefore(request.getStartsAt())); // 시작 시각 지남, 스케줄러 처리 전
        if (proposal.isDeadlinePassed(now) || started) {
            throw new BusinessException(REQUEST_PROPOSAL_EXPIRED); // 기한 지남 또는 요청 시작됨
        }
        if (!proposal.getAssignment().isCurrentAssignee()) {
            throw new BusinessException(REQUEST_PROPOSAL_NOT_PENDING); // 방어: 지정이 마감되면 수정안도 무효가 됨
        }
        return proposal;
    }
}