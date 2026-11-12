package com.gyeot.domain.carerequest.query;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/**
 * 기록상 담당자 = "요청이 끝날 때 담당자였던 사람"을 찾는 규칙입니다. (F-22, #63 지난 상황 기록, #64 도움 기록)
 * 지난 상황 기록과 도움 기록이 같은 사람을 담당자로 보여 주도록 이 규칙 한 곳만 씁니다.
 *
 * 단순히 "수락한 적 있음(accepted_at)"으로 보면, 수락했다가 철회한 사람도 담당자로 잡힙니다.
 * (예: 수락 → 수락 철회 → 다른 사람 없이 만료되면, 철회한 사람의 "도와준 기록"에 뜸)
 * 그래서 아래처럼 요청이 시작됐는지로 나눠 판단합니다.
 *
 * - 시작한 요청(started_at 있음): 마지막으로 수락한 지정
 *   시작 후에는 수락 철회, 다시 부탁하기가 안 되므로 시작할 때의 담당자가 끝까지 담당자입니다.
 *   진행 중에 담당자가 상황을 나가 취소된 경우도 그 사람이 담당자입니다.
 * - 시작 전에 끝난 요청: 끝날 때까지 열려 있었거나(완료된 급한 요청) 요청 종료로 함께 마감된(REQUEST_ENDED) 수락 지정
 *   수락 철회(ACCEPTANCE_WITHDRAWN), 시작 전 이탈로 마감된 지정은 담당자가 아닙니다.
 *
 * CareRequestRepository.findHelpRecords의 JPQL 조건도 이 규칙과 같게 작성했습니다. 바꿀 때는 둘을 함께 바꿉니다.
 */
final class RecordAssigneeRule {
    private RecordAssigneeRule() {
    }

    /**
     * @param request     끝난 요청
     * @param assignments 그 요청의 지정 전체
     * @return 기록상 담당자의 지정. 없으면(담당자 없이 만료 등) 빈 Optional
     */
    static Optional<RequestAssignment> find(CareRequest request, Collection<RequestAssignment> assignments) {
        if (request.getStartedAt() != null) {
            return assignments.stream()
                    .filter(RequestAssignment::isAccepted)
                    .max(Comparator.comparingInt(RequestAssignment::getAssignmentOrder));
        }
        return assignments.stream()
                .filter(RequestAssignment::isAccepted)
                .filter(a -> a.isOpen() || a.getCloseReason() == AssignmentCloseReason.REQUEST_ENDED)
                .findFirst();
    }
}
