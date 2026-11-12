package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import static com.gyeot.domain.carerequest.exception.CareRequestErrorCode.REQUEST_NOT_FOUND;

/**
 * 요청 조회 + 관련자 확인을 한곳에 모은 공통 컴포넌트입니다.
 * 관련 없는 사람에게는 404(REQUEST_NOT_FOUND)를 돌려줍니다. (API 공통 규칙 참조)
 */

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CareRequestFinder {

    private final CareRequestRepository careRequestRepository;
    private final RequestAssignmentRepository assignmentRepository;

    /**
     * 요청만 조회합니다. 없으면 404를 반환합니다.
     * 관련자 확인이 필요 없는 내부 처리 용도입니다.
     */
    public CareRequest getRequest(Long requestId) {
        return careRequestRepository.findById(requestId)
                .orElseThrow(() -> new
                        BusinessException(REQUEST_NOT_FOUND));
    }

    /**
     * 요청을 조회하고 보는 사람의 역할을 판단합니다.
     * - 요청자 → REQUESTER
     * - 이 요청에 지정된 적이 있는 사람(과거 대상 포함) → TARGET 또는 ASSIGNEE
     * - 그 외 → 404 REQUEST_NOT_FOUND
     */
    public RequestAccess getAccessibleRequest(Long requestId, Long userId) {
        CareRequest request = getRequest(requestId);

        if (request.getRequesterId().equals(userId)) {
            return RequestAccess.ofRequester(request);
        }

        return assignmentRepository.findByCareRequestIdAndTargetUserId(requestId, userId)
                .map(assignment -> RequestAccess.ofAssignee(request, assignment))
                .orElseThrow(() -> new BusinessException(REQUEST_NOT_FOUND));
    }
}
