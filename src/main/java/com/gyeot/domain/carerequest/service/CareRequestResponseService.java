package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.carerequest.dto.response.RespondResponse;
import com.gyeot.domain.carerequest.dto.response.WithdrawResponse;
import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.entity.RequestChangeEvent;
import com.gyeot.domain.carerequest.entity.RequestChangeProposal;
import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.InvalidationReason;
import com.gyeot.domain.carerequest.enums.ProposalStatus;
import com.gyeot.domain.carerequest.event.CareRequestAcceptanceWithdrawnEvent;
import com.gyeot.domain.carerequest.event.CareRequestAcceptedEvent;
import com.gyeot.domain.carerequest.event.CareRequestRespondedEvent;
import com.gyeot.domain.carerequest.repository.RequestChangeEventRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeProposalRepository;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;

import static com.gyeot.domain.carerequest.exception.CareRequestErrorCode.*;

/**
 * 요청 응답(F-06)과 수락 철회(F-08)를 처리합니다.
 *
 * 처리 순서는 두 메서드가 같습니다.
 * 1. 요청 행을 잠그고 조회 + 관련자 확인 (동시에 들어온 다른 작업은 여기서 기다림, F-07 중복 수락 방지)
 * 2. 지금 할 수 있는 동작인지 확인 → 안 되면 예외 발생 (엔티티 메서드를 부르기 전에 서비스에서 먼저 거름)
 * 3. 바뀌기 전 값을 꺼내 둠 → 엔티티 상태 변경
 * 4. 이력(request_change_events) 저장
 * 5. 다른 영역에 알리는 이벤트 발행 (D 권한, B 알림)
 *
 * 엔티티 메서드도 잘못된 상태면 IllegalStateException을 던지고 그것은 500(서버 오류)으로 응답됩니다.
 * 서비스에서 먼저 확인해서 이유가 담긴 409를 반환해 주기 위해 2번 순서에서 먼저 거르도록 구현하였습니다.
 */
@Service
@RequiredArgsConstructor
@Transactional // 메서드 하나가 트랜잭션 하나. 중간에 예외가 나면 전부 되돌림(롤백)
public class CareRequestResponseService {
    private final CareRequestFinder careRequestFinder;
    private final RequestChangeProposalRepository proposalRepository;
    private final RequestChangeEventRepository eventRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;


    // === 요청 응답 (POST /api/care-requests/{requestId}/respond) ===
    public RespondResponse respond(Long requestId, Long userId, AssignmentResponseStatus response) {
        Objects.requireNonNull(response, "response는 필수입니다.");
        LocalDateTime now = LocalDateTime.now(clock);

        // 1. 잠그고 조회하고, 관련자를 확인합니다.
        RequestAccess access = careRequestFinder.getAccessibleRequestForUpdate(requestId, userId);
        if (access.isRequester()) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN); // 요청자는 자기 요청에 응답할 수 없음
        }
        CareRequest request = access.request();
        RequestAssignment assignment = access.myAssignment();

        // 2. 응답할 수 있는지 확인합니다.
        validateRespondable(request, assignment, now);

        // 같은 응답을 다시 누르면(중복 클릭) 아무것도 바꾸지 않고 지금 상태를 돌려줌
        if (assignment.getResponseStatus() == response) {
            return RespondResponse.of(request, assignment);
        }

        // 어려움 → 확인 후 답변은 막음 (명세는 어려움에서 수락으로만 바꿀 수 있게 정함, F-06)
        // 어려움을 받으면 요청자에게 담당자 없음 모달이 바로 뜨므로, 다시 응답 대기로 돌아가면 요청자가 헷갈릴 경우를 대비
        if (assignment.getResponseStatus() == AssignmentResponseStatus.UNAVAILABLE
                && response == AssignmentResponseStatus.CHECK_LATER) {
            throw new BusinessException(REQUEST_RESPONSE_NOT_CHANGEABLE);
        }

        // 3. 바뀌기 전 값 꺼내 두고, 상태를 변경합니다.
        AssignmentResponseStatus previousResponse = assignment.getResponseStatus();
        CareRequestStatus previousStatus = request.getStatus();

        assignment.respond(response, now);
        boolean accepted = response == AssignmentResponseStatus.AVAILABLE;
        if (accepted) {
            request.accept(); // 지정 응답 → 요청 상태 순서
        }

        // 4. 이력를 저장합니다. 모든 응답은 RESPONSE_CHANGED, 수락이면 상태 변경도 함께 저장합니다.
        eventRepository.save(RequestChangeEvent.responseChanged(assignment, previousResponse, userId, now));
        if (accepted) {
            eventRepository.save(RequestChangeEvent.statusChanged(request, previousStatus, userId, now));
        }

        // 5. 이벤트를 발행합니다. 도움 가능은 AcceptedEvent만, 나머지는 RespondedEvent (알림 중복 방지) 입니다.
        if (accepted) {
            eventPublisher.publishEvent(CareRequestAcceptedEvent.of(assignment, now));
        } else {
            eventPublisher.publishEvent(CareRequestRespondedEvent.of(assignment, now));
        }

        return RespondResponse.of(request, assignment);
    }

    /**
     * 응답할 수 있는지 확인합니다. 위에서부터 순서대로 확인해, 가장 먼저 걸린 이유로 응답합니다.
     * 엔티티의 respond()가 확인하는 조건을 같은 순서로, 사용자에게 보여 줄 에러 코드로 바꿔 두었습니다.
     */
    private void validateRespondable(CareRequest request, RequestAssignment assignment, LocalDateTime now) {
        if (request.isTerminal()) {
            throw new BusinessException(REQUEST_ALREADY_ENDED);      // 취소·만료·완료 → 종료 사유를 보여 줌
        }
        if (!assignment.isOpen()) {
            throw new BusinessException(REQUEST_ASSIGNMENT_CLOSED);  // 다른 분께 넘어감, 수락 철회 등으로 마감
        }
        if (assignment.isAccepted()) {
            throw new BusinessException(REQUEST_ALREADY_ACCEPTED);   // 이미 수락함 (중복 클릭, 여러 기기)
        }
        if (request.getStatus() != CareRequestStatus.REQUESTED) {
            throw new BusinessException(REQUEST_ALREADY_ACCEPTED);   // 열린 지정은 하나뿐이라 실제로는 오지 않는 경우 (방어)
        }
        if (!request.isUrgent() && !now.isBefore(request.getStartsAt())) {
            throw new BusinessException(REQUEST_ALREADY_STARTED);    // 일반 요청: 응답 기한이 지나도 시작 전이면 가능
        }
        // TODO: 급한 요청(F-13, SHOULD)의 30분 기한 경과 응답은 1차 추가 때 에러 코드와 함께 정합니다.
    }

    // === 수락 철회 (POST /api/care-requests/{requestId}/withdraw) ===
    public WithdrawResponse withdraw(Long requestId, Long userId) {
        LocalDateTime now = LocalDateTime.now(clock);

        // 1. 잠그고 조회하고 관련자를 확인합니다.
        RequestAccess access = careRequestFinder.getAccessibleRequestForUpdate(requestId, userId);
        CareRequest request = access.request();
        RequestAssignment assignment = access.myAssignment();

        // 2. 철회할 수 있는지 확인합니다.
        validateWithdrawable(access, now);

        // 3. 바뀌기 전 값을 꺼내 두고, 상태를 변경합니다.
        CareRequestStatus previousStatus = request.getStatus();
        RequestChangeProposal pendingProposal = proposalRepository
                .findByCareRequestIdAndStatus(request.getId(), ProposalStatus.PENDING)
                .orElse(null);

        assignment.close(AssignmentCloseReason.ACCEPTANCE_WITHDRAWN, now); // 이 지정은 다시 수락할 수 없게 됨
        request.revertToRequested();                                       // ACCEPTED → REQUESTED
        if (pendingProposal != null) {
            pendingProposal.invalidate();                                  // 재확인 받을 담당자가 없어졌으므로 무효
        }

        // 4. 이력을 저장합니다. 지정 마감 + 상태 변경 (+ 수정안 무효화)
        eventRepository.save(RequestChangeEvent.assignmentClosed(assignment, userId, now));
        eventRepository.save(RequestChangeEvent.statusChanged(request, previousStatus, userId, now));
        if (pendingProposal != null) {
            eventRepository.save(RequestChangeEvent.reconfirmationInvalidated(
                    pendingProposal, InvalidationReason.ASSIGNMENT_CLOSED, userId, now));
        }

        // 5. 이벤트를 발행합니다. D는 이 지정의 권한 회수, B는 요청자에게 철회 알림을 보냅니다.
        eventPublisher.publishEvent(CareRequestAcceptanceWithdrawnEvent.of(assignment, now));

        return new WithdrawResponse(request.getId(), request.getStatus());
    }

    private void validateWithdrawable(RequestAccess access, LocalDateTime now) {
        CareRequest request = access.request();
        if (access.isRequester()) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);  // 요청자는 철회할 수 없음
        }
        if (request.isTerminal()) {
            throw new BusinessException(REQUEST_ALREADY_ENDED);
        }
        if (!access.myAssignment().isCurrentAssignee()) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);  // 수락 전 대상, 이전 대상
        }
        if (request.getStatus() == CareRequestStatus.IN_PROGRESS) {
            throw new BusinessException(REQUEST_NOT_WITHDRAWABLE);   // 진행 중에는 철회 불가
        }

        // 시작 시각은 지났는데 스케줄러가 아직 진행 중으로 바꾸기 전인 경우도 시작된 것으로 봄
        if (!request.isUrgent() && !now.isBefore(request.getStartsAt())) {
            throw new BusinessException(REQUEST_NOT_WITHDRAWABLE);
        }
    }



}
