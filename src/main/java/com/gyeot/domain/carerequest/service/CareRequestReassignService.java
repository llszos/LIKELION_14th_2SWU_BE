package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.carerequest.dto.request.ReassignRequest;
import com.gyeot.domain.carerequest.dto.response.ReassignResponse;
import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.entity.RequestChangeEvent;
import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.event.CareRequestTargetChangedEvent;
import com.gyeot.domain.carerequest.query.RequestDisplayStatus;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeEventRepository;
import com.gyeot.domain.safetycard.query.SafetyInformationSharingReader;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

import static com.gyeot.domain.carerequest.exception.CareRequestErrorCode.*;

/**
 * 다시 부탁하기를 처리합니다. (#71, F-05·F-14·F-03)
 * POST /api/care-requests/{requestId}/assignments
 *
 * 처리 순서
 * 1. 요청 행을 잠그고 요청자인지 확인 (아니면 403, 관련 없으면 Finder가 404)
 * 2. 다시 부탁할 수 있는 상태인지 확인
 *    - 취소·만료·완료 → 409 REQUEST_ALREADY_ENDED
 *    - 진행 중이거나 시작 시각이 지남(스케줄러 처리 전 포함) → 409 REQUEST_ALREADY_STARTED
 *    - 담당자 없음이 아님(응답 대기 중, 수락됨) → 409 REQUEST_NOT_REASSIGNABLE
 * 3. 입력 확인: 응답 기한 1~72시간(400 REQUEST_DEADLINE_INVALID)
 * 4. 새 대상 확인
 *    - 이 요청에서 이미 부탁했던 사람 → 409 REQUEST_TARGET_ALREADY_ASSIGNED
 *    - 본인, 상황 참여자 아님, 나의 사람·서로의 보호자 아님 → 400 REQUEST_TARGET_INVALID
 * 5. 공개 항목 확인: D가 검증 (400 SAFETY_INFO_INVALID_SELECTION, 예외 그대로)
 * 6. 이전 지정이 열려 있으면 마감(TARGET_CHANGED) → 새 지정 저장(순서 +1) → 이력 TARGET_CHANGED → 이벤트
 *    (D: 이전 지정 권한 회수 + 새 지정 공유 예정 권한 생성 / B: 새 대상에게 요청 알림)
 *
 * 새 요청과 달리 시작까지 1시간 미만이어도 보낼 수 있습니다. 응답 기한은 시작 시각으로 제한됩니다. (F-05)
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CareRequestReassignService {

    private final CareRequestFinder careRequestFinder;
    private final RequestAssignmentRepository assignmentRepository;
    private final RequestChangeEventRepository eventRepository;
    private final RequestTargetEligibility targetEligibility;   // B 참여자 + A 관계
    private final SafetyInformationSharingReader sharingReader; // D: 공개 항목 검증
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public ReassignResponse reassign(Long requestId, Long userId, ReassignRequest body) {
        // 1. 잠그고 조회 + 요청자 확인
        RequestAccess access = careRequestFinder.getAccessibleRequestForUpdate(requestId, userId);
        LocalDateTime now = LocalDateTime.now(clock); // 잠금을 얻은 뒤의 시각
        if (!access.isRequester()) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN); // 대상자·담당자는 다시 부탁할 수 없음
        }
        CareRequest request = access.request();

        // 2. 다시 부탁할 수 있는 상태인지 (담당자 없음 판단은 화면 상태와 같은 RequestDisplayStatus 기준)
        RequestAssignment previous = assignmentRepository
                .findFirstByCareRequestIdOrderByAssignmentOrderDesc(requestId).orElse(null);
        validateReassignable(request, previous, now);

        // 3. 입력 확인
        int deadlineHours = ResponseDeadlinePolicy.resolveHours(body.responseDeadlineHours());

        // 4. 새 대상 확인 (이미 부탁했던 사람을 먼저 봄: 이전 대상은 나갔거나 관계가 끊겼어도 "이미 부탁함"으로 안내)
        if (assignmentRepository.existsByCareRequestIdAndTargetUserId(requestId, body.targetUserId())) {
            throw new BusinessException(REQUEST_TARGET_ALREADY_ASSIGNED);
        }
        if (!targetEligibility.isEligible(request.getSituationId(), request.getRequesterId(), body.targetUserId())) {
            throw new BusinessException(REQUEST_TARGET_INVALID);
        }

        // 5. 공개 항목 확인 (D)
        sharingReader.validateSelection(request.getRequesterId(), body.informationIds());

        // 6. 이전 지정 마감 → 새 지정 저장
        if (previous.isOpen()) {
            previous.close(AssignmentCloseReason.TARGET_CHANGED, now); // 어려움·기한 지남으로 아직 열려 있던 지정
        }
        RequestAssignment next = saveNewAssignment(request, previous, body, deadlineHours, now);

        eventRepository.save(RequestChangeEvent.targetChanged(next, previous, userId, now));
        // D: 이전 지정 권한 회수 + 새 지정 PENDING 권한 생성 / B: 새 대상에게 요청 알림
        eventPublisher.publishEvent(CareRequestTargetChangedEvent.of(previous, next, body.informationIds(), now));
        return ReassignResponse.of(next);
    }

    // === 2. 상태 확인 ===

    private void validateReassignable(CareRequest request, RequestAssignment latest, LocalDateTime now) {
        if (request.isTerminal()) {
            throw new BusinessException(REQUEST_ALREADY_ENDED);     // 취소·만료·완료
        }
        boolean started = request.getStatus() == CareRequestStatus.IN_PROGRESS
                || (!request.isUrgent() && !now.isBefore(request.getStartsAt())); // 시작 시각 지남, 스케줄러 처리 전
        if (started) {
            throw new BusinessException(REQUEST_ALREADY_STARTED);
        }
        // 지정이 하나도 없는 요청은 생성 API로는 만들어지지 않지만, 대상 변경 이력을 남길 이전 지정이 없어 다시 부탁할 수 없음
        if (latest == null || RequestDisplayStatus.of(request, latest, now) != RequestDisplayStatus.NO_ASSIGNEE) {
            throw new BusinessException(REQUEST_NOT_REASSIGNABLE); // 응답 대기 중이거나 이미 수락됨
        }
    }

    // === 6. 새 지정 저장 ===

    /**
     * 지정 순서는 마지막 지정 + 1입니다. 요청 행을 잠근 상태라 동시에 다시 부탁해도 순서가 겹치지 않습니다.
     * 같은 요청·같은 대상 UNIQUE 위반은 잠금 때문에 보통 생기지 않지만, 생기면 이미 부탁한 사람으로 안내합니다.
     */
    private RequestAssignment saveNewAssignment(CareRequest request, RequestAssignment previous, ReassignRequest body,
                                                int deadlineHours, LocalDateTime now) {
        RequestAssignment next = RequestAssignment.assign(request, body.targetUserId(),
                previous.getAssignmentOrder() + 1,
                ResponseDeadlinePolicy.deadline(now, deadlineHours, request.getStartsAt()),
                body.responseDeadlineHours()); // 고른 값 그대로 저장 (null이면 기본값 규칙, N-07 복제용)
        try {
            return assignmentRepository.saveAndFlush(next);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(REQUEST_TARGET_ALREADY_ASSIGNED);
        }
    }
}
