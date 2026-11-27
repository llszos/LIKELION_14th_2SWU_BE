package com.gyeot.domain.carerequest.listener;

import com.gyeot.domain.carerequest.service.CareRequestCleanupService;
import com.gyeot.domain.relation.event.RelationshipEndedEvent;
import com.gyeot.domain.situation.event.SituationEndedEvent;
import com.gyeot.domain.situation.event.SituationParticipantRemovedEvent;
import com.gyeot.domain.user.event.UserWithdrawnEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 다른 영역 이벤트를 받아 C 요청 정리로 넘깁니다. (#76, 이벤트 처리 영역 표: B·A → C)
 * - 동기 @EventListener: 이벤트를 보낸 쪽 트랜잭션 안에서 처리합니다.
 * - 예외를 잡지 않습니다. C 처리가 실패하면 상황 종료·참여 종료·관계 해제·탈퇴까지 함께 롤백됩니다. (이벤트 처리 규칙 3번)
 * - C 처리 중 발행하는 C 이벤트(취소, 지정 마감)는 D가 같은 트랜잭션에서 권한 회수로 이어 받습니다.
 */
@Component
@RequiredArgsConstructor
public class CareRequestCleanupListener {

    private final CareRequestCleanupService cleanupService;

    /** 상황 종료 → 끝나지 않은 요청 취소 (SITUATION_ENDED) */
    @EventListener
    public void onSituationEnded(SituationEndedEvent event) {
        cleanupService.cancelRequestsOfEndedSituation(event.situationId(), event.occurredAt());
    }

    /** 참여 종료(나가기·제외) → 그 사람이 대상자·담당자인 요청 정리 */
    @EventListener
    public void onParticipantRemoved(SituationParticipantRemovedEvent event) {
        cleanupService.removeParticipant(event.situationId(), event.userId(), event.reason(), event.occurredAt());
    }

    /** 관계 해제 → 상황 밖 요청(급한 요청) 정리 */
    @EventListener
    public void onRelationshipEnded(RelationshipEndedEvent event) {
        cleanupService.removeRelationship(event.sourceUserId(), event.targetUserId(), event.relationshipType(),
                event.occurredAt());
    }

    /**
     * 회원 탈퇴 → 내 요청 취소, 내가 맡은 지정 마감
     * 처리 순서: B(100) → C(200) → D(300). C 요청 정리가 D 원본 정리보다 먼저 실행돼 C 요청 → D 항목 잠금 순서를 지킴 (#159)
     */
    @EventListener
    @Order(200)
    public void onUserWithdrawn(UserWithdrawnEvent event) {
        cleanupService.removeWithdrawnUser(event.userId(), event.occurredAt());
    }
}
