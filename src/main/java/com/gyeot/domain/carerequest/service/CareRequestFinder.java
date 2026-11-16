package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

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
     * 요청을 조회하고 보는 사람의 역할을 판단합니다. (조회 API용, 잠그지 않음)
     * - 요청자 → REQUESTER
     * - 이 요청에 지정된 적이 있는 사람(과거 대상 포함) → TARGET 또는 ASSIGNEE
     * - 그 외 → 404 REQUEST_NOT_FOUND
     */
    public RequestAccess getAccessibleRequest(Long requestId, Long userId) {
        return toAccess(getRequest(requestId), requestId, userId);
    }

    /**
     * 요청 행을 잠그고 조회한 뒤 역할을 판단합니다. (상태를 바꾸는 API용)
     * 판단 규칙은 getAccessibleRequest와 같습니다.
     * 호출하는 쪽에 트랜잭션이 반드시 있어야 합니다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public RequestAccess getAccessibleRequestForUpdate(Long requestId, Long userId) {
        CareRequest request = careRequestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new BusinessException(REQUEST_NOT_FOUND));
        return toAccess(request, requestId, userId);
    }

    private RequestAccess toAccess(CareRequest request, Long requestId, Long userId) {
        if (request.getRequesterId().equals(userId)) {
            return RequestAccess.ofRequester(request);
        }

        return assignmentRepository.findByCareRequestIdAndTargetUserId(requestId, userId)
                .map(assignment -> RequestAccess.ofAssignee(request, assignment))
                .orElseThrow(() -> new BusinessException(REQUEST_NOT_FOUND));
    }
}
