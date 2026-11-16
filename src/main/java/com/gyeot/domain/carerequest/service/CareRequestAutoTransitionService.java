package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestChangeEvent;
import com.gyeot.domain.carerequest.entity.RequestChangeProposal;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.InvalidationReason;
import com.gyeot.domain.carerequest.enums.ProposalStatus;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeEventRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeProposalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 시간이 되면 서버가 요청 상태를 바꾸는 처리를 요청 한 건씩 담당합니다. (#75, F-08, F-29)
 * CareRequestAutoTransitionScheduler가 대상 ID를 모아 한 건씩 부릅니다.
 *
 * 메서드마다 지키는 규칙
 * - 건마다 새 트랜잭션(REQUIRES_NEW)입니다. 한 건이 실패해도 그 건만 되돌리고 다른 건 처리는 계속됩니다.
 * - 요청 행을 먼저 잠근 뒤(findByIdForUpdate) 상태와 시각을 다시 확인합니다.
 *   스케줄러가 ID를 모은 뒤 사용자가 취소하거나, 시작을 누르거나, 일정을 바꿨을 수 있기 때문입니다.
 *   사용자 API와 같은 요청 행 잠금을 쓰므로, 같은 요청에 대한 사용자 동작과 차례로 처리됩니다.
 * - 이미 처리됐거나 조건이 맞지 않으면 아무것도 하지 않고 false를 돌려줍니다. (두 번 실행돼도 결과가 같음)
 * - 이력의 수행자는 NULL입니다. (시스템 처리, ERD 4.9)
 * - 시간 기반 자동 처리는 일반 요청에만 적용합니다. 급한 요청(F-13)은 1차 추가 범위라 건너뜁니다.
 */
@Service
@RequiredArgsConstructor
public class CareRequestAutoTransitionService {
    private final CareRequestRepository careRequestRepository;
    private final RequestChangeProposalRepository proposalRepository;
    private final RequestChangeEventRepository eventRepository;
    private final CareRequestLifecycle lifecycle;

    /** 시작 시각 도달: ACCEPTED → IN_PROGRESS (대기 중 수정안은 REQUEST_STARTED로 무효, Lifecycle) */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean startIfDue(Long requestId, LocalDateTime now) {
        Optional<CareRequest> due = lockGeneral(requestId)
                .filter(request -> request.getStatus() == CareRequestStatus.ACCEPTED)
                .filter(request -> !now.isBefore(request.getStartsAt())); // 일정이 뒤로 바뀌었으면 아직 아님
        due.ifPresent(request -> lifecycle.start(request, null, now));
        return due.isPresent();
    }

    /** 시작 시각 도달: REQUESTED → EXPIRED (시작 시각까지 담당자 없음, 열린 지정 마감) */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expireIfDue(Long requestId, LocalDateTime now) {
        Optional<CareRequest> due = lockGeneral(requestId)
                .filter(request -> request.getStatus() == CareRequestStatus.REQUESTED)
                .filter(request -> !now.isBefore(request.getStartsAt()));
        due.ifPresent(request -> lifecycle.expire(request, now));
        return due.isPresent();
    }

    /** 종료 시각 도달: IN_PROGRESS → COMPLETED (완료한 사용자 없음) */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean completeIfDue(Long requestId, LocalDateTime now) {
        Optional<CareRequest> due = lockGeneral(requestId)
                .filter(request -> request.getStatus() == CareRequestStatus.IN_PROGRESS)
                .filter(request -> !now.isBefore(request.getEndsAt()));
        due.ifPresent(request -> lifecycle.complete(request, null, now));
        return due.isPresent();
    }

    /**
     * 재확인 기한 지남: 대기 중 수정안 → INVALIDATED(DEADLINE_PASSED) (F-29)
     * 요청은 그대로 두고 기존 내용, 수락, 공개 권한을 유지합니다. 그래서 다른 영역에 보낼 이벤트가 없습니다.
     * 수정안이 아니라 요청 ID를 받습니다. 요청 행을 잠근 뒤에 수정안을 읽어야 최신 상태로 판단할 수 있습니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean invalidateExpiredProposal(Long requestId, LocalDateTime now) {
        if (careRequestRepository.findByIdForUpdate(requestId).isEmpty()) {
            return false;
        }
        Optional<RequestChangeProposal> expired = proposalRepository
                .findByCareRequestIdAndStatus(requestId, ProposalStatus.PENDING) // 잠금 뒤에 읽어 최신 상태
                .filter(proposal -> proposal.isDeadlinePassed(now)); // 그사이 다시 수정해 기한이 새로 잡혔으면 아님
        expired.ifPresent(proposal -> {
            proposal.invalidate();
            eventRepository.save(RequestChangeEvent.reconfirmationInvalidated(
                    proposal, InvalidationReason.DEADLINE_PASSED, null, now));
        });
        return expired.isPresent();
    }

    /** 요청 행을 잠그고, 일반 요청이면 돌려줍니다. (없거나 급한 요청이면 빈 값) */
    private Optional<CareRequest> lockGeneral(Long requestId) {
        return careRequestRepository.findByIdForUpdate(requestId)
                .filter(request -> !request.isUrgent());
    }
}