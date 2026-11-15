package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;
import com.gyeot.domain.carerequest.enums.CancelReason;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.RequestType;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.domain.relation.entity.RelationshipType;
import com.gyeot.domain.situation.event.SituationParticipantRemovalReason;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 다른 영역(B 상황, A 관계·회원) 이벤트를 받아 관련 요청과 대상 지정을 정리합니다. (#76, ERD 9.3·9.4·9.6)
 * CareRequestCleanupListener가 이벤트를 넘기며, 이벤트를 보낸 쪽 트랜잭션 안에서 함께 처리됩니다.
 * (예외를 잡지 않아 실패하면 원래 작업까지 롤백, 이벤트 처리 규칙 3번)
 *
 * 처리 방식 (모든 이벤트 공통)
 * 1. 처리할 요청 ID를 모읍니다.
 * 2. 요청 ID 순서로 요청 행을 하나씩 잠급니다. 여러 요청을 잠글 때 순서를 같게 해 서로 기다리다 멈추는 일(데드락)을 막습니다.
 * 3. 잠근 뒤 최신 값으로 다시 확인합니다. 그사이 끝났거나 지정이 바뀐 요청은 건너뜁니다.
 *    그래서 같은 이벤트를 두 번 받아도 두 번째는 아무것도 바꾸지 않습니다.
 * 4. 상태 변경은 CareRequestLifecycle(취소, 대상자·담당자 빠짐)에 맡깁니다. 이력과 C 이벤트(D 권한 회수 등)도 거기서 남깁니다.
 *
 * 다른 영역 이벤트로 바뀐 것이라 이력의 actor_user_id는 null(시스템 처리)입니다.
 */
@Service
@RequiredArgsConstructor
@Transactional // 이벤트를 보낸 쪽 트랜잭션에 참여 (동기 @EventListener)
public class CareRequestCleanupService {

    private static final List<CareRequestStatus> ONGOING =
            List.of(CareRequestStatus.REQUESTED, CareRequestStatus.ACCEPTED, CareRequestStatus.IN_PROGRESS);

    private final CareRequestRepository careRequestRepository;
    private final RequestAssignmentRepository assignmentRepository;
    private final CareRequestLifecycle lifecycle;

    /**
     * 상황 종료 (F-04): 상황의 끝나지 않은 요청을 모두 취소합니다. (SITUATION_ENDED)
     * 열린 지정은 요청 종료로 함께 마감되고, D는 요청의 모든 권한을 회수합니다.
     */
    public void cancelRequestsOfEndedSituation(Long situationId, LocalDateTime occurredAt) {
        for (Long requestId : careRequestRepository.findIdsBySituationIdAndStatusIn(situationId, ONGOING)) {
            CareRequest request = lock(requestId);
            if (!request.isTerminal()) {
                lifecycle.cancel(request, CancelReason.SITUATION_ENDED, null, occurredAt);
            }
        }
    }

    /**
     * 상황 참여 종료 (나가기·제외, ERD 9.3): 그 상황에서 이 사람이 지금 대상자·담당자인 요청을 정리합니다.
     * - 원인 PARTICIPANT_LEFT: 지정 마감 PARTICIPANT_LEFT, 진행 중이면 요청 취소 ASSIGNEE_LEFT
     * - 원인 RELATIONSHIP_ENDED: 지정 마감 RELATIONSHIP_ENDED, 진행 중이면 요청 취소 RELATIONSHIP_ENDED
     */
    public void removeParticipant(Long situationId, Long userId, SituationParticipantRemovalReason reason,
                                  LocalDateTime occurredAt) {
        AssignmentCloseReason closeReason = reason == SituationParticipantRemovalReason.RELATIONSHIP_ENDED
                ? AssignmentCloseReason.RELATIONSHIP_ENDED : AssignmentCloseReason.PARTICIPANT_LEFT;
        CancelReason cancelReason = reason == SituationParticipantRemovalReason.RELATIONSHIP_ENDED
                ? CancelReason.RELATIONSHIP_ENDED : CancelReason.ASSIGNEE_LEFT;

        for (Long requestId : assignmentRepository.findOpenRequestIdsInSituation(situationId, userId)) {
            removeAssignee(requestId, userId, closeReason, cancelReason, occurredAt);
        }
    }

    /**
     * 관계 해제 (ERD 9.4): 상황 밖에서 보낸 요청(급한 요청)만 정리합니다.
     * 상황 안의 일반 요청은 B가 관계 해제로 참여자를 제외하며 보내는 참여 종료 이벤트(RELATIONSHIP_ENDED)로 정리됩니다.
     * 나의 사람은 source → target 방향만, 서로의 보호자는 양쪽 방향을 정리합니다. (이벤트는 한 번만 옴)
     * 급한 요청(F-13)은 SHOULD라 아직 만들어지지 않아 지금은 처리할 요청이 없습니다.
     */
    public void removeRelationship(Long sourceUserId, Long targetUserId, RelationshipType relationshipType,
                                   LocalDateTime occurredAt) {
        removeUrgentAssignee(sourceUserId, targetUserId, occurredAt);
        if (relationshipType == RelationshipType.GUARDIAN) {
            removeUrgentAssignee(targetUserId, sourceUserId, occurredAt);
        }
    }

    /**
     * 회원 탈퇴 (ERD 9.6)
     * - 요청자로서: 끝나지 않은 내 요청을 취소합니다. (USER_WITHDRAWN)
     * - 대상자·담당자로서: 지금 맡은 지정을 마감합니다. (USER_WITHDRAWN, 진행 중이면 요청 취소 USER_WITHDRAWN)
     * 두 목록을 합쳐 요청 ID 순서로 처리합니다. (두 사람이 동시에 탈퇴해도 잠그는 순서가 같음)
     * TODO: 탈퇴한 사람이 보낸 요청의 삭제는 팀 결정 후 추가합니다. (삭제되는 요청의 이벤트 발행 방식 미정, #76)
     */
    public void removeWithdrawnUser(Long userId, LocalDateTime occurredAt) {
        Collection<Long> requestIds = new TreeSet<>(careRequestRepository.findIdsByRequesterIdAndStatusIn(userId, ONGOING));
        requestIds.addAll(assignmentRepository.findOpenRequestIdsByTarget(userId));

        for (Long requestId : requestIds) {
            CareRequest request = lock(requestId);
            if (request.isTerminal()) {
                continue;
            }
            if (Objects.equals(request.getRequesterId(), userId)) {
                lifecycle.cancel(request, CancelReason.USER_WITHDRAWN, null, occurredAt);
            } else {
                removeAssigneeOf(request, userId, AssignmentCloseReason.USER_WITHDRAWN,
                        CancelReason.USER_WITHDRAWN, occurredAt);
            }
        }
    }

    // === 내부 처리 ===

    private void removeUrgentAssignee(Long requesterId, Long userId, LocalDateTime occurredAt) {
        for (Long requestId : assignmentRepository.findOpenRequestIdsByRequesterAndTarget(
                requesterId, userId, RequestType.URGENT)) {
            removeAssignee(requestId, userId, AssignmentCloseReason.RELATIONSHIP_ENDED,
                    CancelReason.RELATIONSHIP_ENDED, occurredAt);
        }
    }

    private void removeAssignee(Long requestId, Long userId, AssignmentCloseReason closeReason,
                                CancelReason cancelReason, LocalDateTime occurredAt) {
        CareRequest request = lock(requestId);
        if (!request.isTerminal()) {
            removeAssigneeOf(request, userId, closeReason, cancelReason, occurredAt);
        }
    }

    /** 잠근 뒤 다시 확인: 이 사람의 지정이 아직 열려 있을 때만 정리 (그사이 다른 사람에게 넘어갔으면 건너뜀) */
    private void removeAssigneeOf(CareRequest request, Long userId, AssignmentCloseReason closeReason,
                                  CancelReason cancelReason, LocalDateTime occurredAt) {
        assignmentRepository.findOpenByRequestIdForUpdate(request.getId())
                .filter(open -> Objects.equals(open.getTargetUserId(), userId))
                .ifPresent(open -> lifecycle.removeAssignee(request, open, closeReason, cancelReason, null, occurredAt));
    }

    /** 요청 행 잠금 (ID로 모은 요청이라 반드시 있음) */
    private CareRequest lock(Long requestId) {
        return careRequestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new IllegalStateException("요청을 찾을 수 없습니다: " + requestId));
    }
}
