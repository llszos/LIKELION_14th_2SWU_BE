package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;

import java.util.Objects;

/**
 * 요청 관련자 확인 결과입니다. (CareRequestFinder.getAccessibleRequest)
 * - request: 조회한 요청
 * - role: 보는 사람의 역할
 * - myAssignment: 보는 사람의 지정. 요청자면 null, 대상자·담당자면 그 사람의 지정 (마감된 지정일 수 있음)
 */
public record RequestAccess(CareRequest request, RequestViewerRole role, RequestAssignment myAssignment) {
    public RequestAccess {
        Objects.requireNonNull(request, "request는 필수입니다.");
        Objects.requireNonNull(role, "role은 필수입니다.");
        if ((role == RequestViewerRole.REQUESTER) != (myAssignment == null)) {
            throw new IllegalArgumentException("요청자는 지정이 없고, 대상자·담당자는 지정이 있어야 합니다.");
        }
    }

    static RequestAccess ofRequester(CareRequest request) {
        return new RequestAccess(request, RequestViewerRole.REQUESTER, null);
    }

    /**
     * 대상자·담당자 판단
     * 수락한 적이 있으면(accepted_at 있음) 담당자, 아니면 대상자입니다.
     * 완료된 요청은 지정을 마감하지 않고, 취소된 요청은 마감하지만 둘 다 담당자로 봅니다.
     * 마감 여부는 화면에서 myAssignment.isOpen()으로 따로 구분합니다.
     */
    static RequestAccess ofAssignee(CareRequest request, RequestAssignment assignment) {
        RequestViewerRole role = assignment.isAccepted() ? RequestViewerRole.ASSIGNEE : RequestViewerRole.TARGET;
        return new RequestAccess(request, role, assignment);
    }

    public boolean isRequester() {
        return role == RequestViewerRole.REQUESTER;
    }

}
