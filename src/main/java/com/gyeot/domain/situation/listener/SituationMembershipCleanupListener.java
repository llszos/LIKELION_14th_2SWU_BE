package com.gyeot.domain.situation.listener;

import com.gyeot.domain.relation.event.RelationshipEndedEvent;
import com.gyeot.domain.situation.service.SituationMembershipCleanupService;
import com.gyeot.domain.user.event.UserWithdrawnEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 커밋 전 동기 처리. 예외를 삼키거나 별도 트랜잭션으로 분리하지 않습니다. */
@Component
@RequiredArgsConstructor
public class SituationMembershipCleanupListener {
    private final SituationMembershipCleanupService service;

    @EventListener
    public void onRelationshipEnded(RelationshipEndedEvent event) {
        service.relationshipEnded(event);
    }

    @EventListener
    public void onUserWithdrawn(UserWithdrawnEvent event) {
        service.userWithdrawn(event);
    }
}
