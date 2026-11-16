package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.entity.RequestChangeEvent;
import com.gyeot.domain.carerequest.entity.RequestChangeProposal;
import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;
import com.gyeot.domain.carerequest.enums.CancelReason;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.InvalidationReason;
import com.gyeot.domain.carerequest.enums.ProposalStatus;
import com.gyeot.domain.carerequest.event.CareRequestCanceledEvent;
import com.gyeot.domain.carerequest.event.CareRequestCompletedEvent;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeEventRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeProposalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 요청의 시작, 완료, 취소 처리를 한곳에 모은 공통 부품입니다. (F-08, ERD 9.1)
 * 사용자 API(#72)뿐 아니라 재확인 거절(#73), 스케줄러(#75), 다른 영역 이벤트 수신(#76)도 이 부품을 씁니다.
 * 누가 불렀는지(권한 확인, 에러 코드)에 관한 부분은 여기서 다루지 않고, 부르는 쪽이 미리 확인합니다.
 *
 * 부르는 쪽이 지켜야 할 약속은 아래와 같습니다.
 * - 요청은 CareRequestFinder.getAccessibleRequestForUpdate 등으로 잠근 상태여야 합니다.
 * - 지금 상태에서 할 수 있는 동작인지 먼저 확인합니다. (여기서 잘못된 상태로 부르면 엔티티가 예외를 던져 500이 됩니다.)
 * - actorUserId: 동작한 사용자. 스케줄러, 시스템 처리면 null (이력의 수행자 NULL, ERD 4.9)
 *
 * 각 메서드가 하는 일을 정리하면
 * - 대기 중 수정안 무효화 → 지정 처리 → 요청 상태 변경 → 이력 저장 → 이벤트 발행
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY) // 잠금이 유지되는 호출자의 트랜잭션 안에서만 실행
public class CareRequestLifecycle {
    private final RequestAssignmentRepository assignmentRepository;
    private final RequestChangeProposalRepository proposalRepository;
    private final RequestChangeEventRepository eventRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 시작: ACCEPTED → IN_PROGRESS
     * 대기 중 수정안은 무효가 되고 기존 내용대로 진행합니다. (REQUEST_STARTED, F-29)
     * 시작은 다른 영역에 알릴 일이 없어 이벤트를 발행하지 않습니다. (권한은 수락 때 이미 활성화됨)
     */
    public void start(CareRequest request, Long actorUserId, LocalDateTime now) {
        CareRequestStatus previousStatus = request.getStatus();

        request.start(now);

        eventRepository.save(RequestChangeEvent.statusChanged(request, previousStatus, actorUserId, now));
        invalidatePendingProposal(request, InvalidationReason.REQUEST_STARTED, actorUserId, now);
    }

    /**
     * 완료: IN_PROGRESS → COMPLETED
     * 완료는 지정을 마감하지 않습니다. 지정 이력과 마지막 담당자를 그대로 남깁니다. (ERD 9.1)
     * completedByUserId: 완료를 누른 사람. 종료 시각 도달로 스케줄러가 처리하면 null
     */
    public void complete(CareRequest request, Long completedByUserId, LocalDateTime now) {
        CareRequestStatus previousStatus = request.getStatus();
        RequestAssignment assignee = currentOpenAssignment(request); // 진행 중이면 반드시 현재 담당자가 있음

        request.complete(now);

        eventRepository.save(RequestChangeEvent.statusChanged(request, previousStatus, completedByUserId, now));
        invalidatePendingProposal(request, InvalidationReason.REQUEST_ENDED, completedByUserId, now); // 진행 중엔 보통 없음 (방어)

        eventPublisher.publishEvent(CareRequestCompletedEvent.of(request, assignee, completedByUserId, now));
    }

    /**
     * 취소: 종료되지 않은 모든 상태 → CANCELED
     * 열린 지정은 REQUEST_ENDED로 함께 마감합니다. (ERD 9.1)
     * 지정 마감 이력, 이벤트는 따로 남기지 않고, 요청 상태 변경 이력과 취소 이벤트가 대신합니다.
     */
    public void cancel(CareRequest request, CancelReason reason, Long actorUserId, LocalDateTime now) {
        CareRequestStatus previousStatus = request.getStatus();
        RequestAssignment openAssignment = currentOpenAssignment(request); // 취소 당시 대상자·담당자 (없으면 null)

        if (openAssignment != null) {
            openAssignment.close(AssignmentCloseReason.REQUEST_ENDED, now);
        }
        request.cancel(reason, now);

        eventRepository.save(RequestChangeEvent.statusChanged(request, previousStatus, actorUserId, now));
        invalidatePendingProposal(request, InvalidationReason.REQUEST_ENDED, actorUserId, now);

        // D: 요청의 모든 권한 회수 / B: 취소 당시 대상자, 담당자에게 알림
        eventPublisher.publishEvent(CareRequestCanceledEvent.of(request, openAssignment, now));
    }

    // === 내부 처리 ===

    private RequestAssignment currentOpenAssignment(CareRequest request) {
        return assignmentRepository.findByCareRequestIdAndClosedAtIsNull(request.getId()).orElse(null);
    }

    /**
     * 대기 중 수정안이 있으면 무효화하고 이력을 남깁니다.
     * 없으면 아무것도 하지 않습니다.
     */
    private void invalidatePendingProposal(CareRequest request, InvalidationReason reason,
                                           Long actorUserId, LocalDateTime now) {
        proposalRepository.findByCareRequestIdAndStatus(request.getId(), ProposalStatus.PENDING)
                .ifPresent(proposal -> {
                    proposal.invalidate();
                    eventRepository.save(RequestChangeEvent.reconfirmationInvalidated(
                            proposal, reason, actorUserId, now));
                });
    }
}
