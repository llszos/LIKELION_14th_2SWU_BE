package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.carerequest.dto.response.CancelResponse;
import com.gyeot.domain.carerequest.dto.response.RequestStatusResponse;
import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.enums.CancelReason;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

import static com.gyeot.domain.carerequest.exception.CareRequestErrorCode.*;


/**
 * 돌봄 시작, 완료, 요청 취소 API의 규칙을 담당합니다. (#72, F-08, F-29)
 * 누가, 지금 할 수 있는지만 여기서 확인하고, 실제 상태 변경은 CareRequestLifecycle에 맡깁니다.
 *
 * 처리 순서는 #70과 같은 틀입니다.
 * - 1. 요청 잠그고 조회 + 관련자 확인
 * - 2. 할 수 있는지 확인(안 되면 403, 409)
 * - 3. Lifecycle 호출 → 결과 반환
 * 현재 시각(now)은 항상 잠금을 얻은 뒤에 구하고, 그 뒤의 검증, 상태 변경, 이력, 이벤트에 같은 값을 씁니다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CareRequestStatusService {
    private final CareRequestFinder careRequestFinder;
    private final CareRequestLifecycle lifecycle;
    private final Clock clock;

    // === 돌봄 시작 (POST /api/care-requests/{requestId}/start) ===
    /**
     * 현재 담당자만 시작할 수 있습니다.
     * 시작 시각 전에 미리 시작할 때 사용합니다. (시작 시각이 되면 스케줄러가 자동 시작)
     */
    public RequestStatusResponse start(Long requestId, Long userId) {
        RequestAccess access = careRequestFinder.getAccessibleRequestForUpdate(requestId, userId);
        LocalDateTime now = LocalDateTime.now(clock); // 잠금을 얻은 뒤의 시각 (잠금 대기 시간이 반영되도록)
        CareRequest request = access.request();

        if (access.isRequester()) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);   // 요청자는 시작할 수 없음
        }
        if (request.isTerminal()) {
            throw new BusinessException(REQUEST_ALREADY_ENDED);
        }
        if (!access.myAssignment().isCurrentAssignee()) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);   // 수락 전 대상, 이전 대상
        }
        if (request.getStatus() == CareRequestStatus.IN_PROGRESS) {
            throw new BusinessException(REQUEST_ALREADY_STARTED);     // 이미 진행 중 (시작 시각 자동 처리 포함)
        }
        // TODO: 급한 요청(F-13, SHOULD)은 시작 단계가 없습니다. 1차 추가 때 막는 에러 코드를 정합니다.

        lifecycle.start(request, userId, now);
        return new RequestStatusResponse(request.getId(), request.getStatus());
    }

    // === 돌봄 완료 (POST /api/care-requests/{requestId}/complete) ===

    /**
     * 현재 담당자와 요청자가 완료할 수 있습니다.
     * 종료 시각 전에도 가능합니다. (종료 시각이 되면 스케줄러가 자동 완료)
     */
    public RequestStatusResponse complete(Long requestId, Long userId) {
        RequestAccess access = careRequestFinder.getAccessibleRequestForUpdate(requestId, userId);
        LocalDateTime now = LocalDateTime.now(clock); // 잠금을 얻은 뒤의 시각 (잠금 대기 시간이 반영되도록)
        CareRequest request = access.request();

        if (request.isTerminal()) {
            throw new BusinessException(REQUEST_ALREADY_ENDED);       // 이미 완료·취소·만료 (종료 시각 자동 완료 포함)
        }
        if (!access.isRequester() && !access.myAssignment().isCurrentAssignee()) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);   // 요청자·현재 담당자가 아님
        }
        if (request.getStatus() != CareRequestStatus.IN_PROGRESS) {
            throw new BusinessException(REQUEST_NOT_STARTED);         // 요청됨·수락됨은 아직 시작 전
        }

        lifecycle.complete(request, userId, now);
        return new RequestStatusResponse(request.getId(), request.getStatus());
    }

    // === 요청 취소 (POST /api/care-requests/{requestId}/cancel) ===

    /**
     * 요청자만 취소할 수 있습니다.
     * 요청됨, 수락됨, 진행 중 모두 취소할 수 있습니다. */
    public CancelResponse cancel(Long requestId, Long userId) {
        RequestAccess access = careRequestFinder.getAccessibleRequestForUpdate(requestId, userId);
        LocalDateTime now = LocalDateTime.now(clock); // 잠금을 얻은 뒤의 시각 (잠금 대기 시간이 반영되도록)
        CareRequest request = access.request();

        if (!access.isRequester()) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);   // 대상자·담당자는 취소할 수 없음
        }
        if (request.isTerminal()) {
            throw new BusinessException(REQUEST_ALREADY_ENDED);
        }

        lifecycle.cancel(request, CancelReason.REQUESTER_CANCELED, userId, now);
        return new CancelResponse(request.getId(), request.getStatus(), request.getCancelReason());
    }
}
