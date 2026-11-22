package com.gyeot.domain.situation.listener;

import com.gyeot.domain.relation.entity.RelationshipType;
import com.gyeot.domain.relation.event.RelationshipEndedEvent;
import com.gyeot.domain.situation.service.SituationMembershipCleanupService;
import com.gyeot.domain.user.event.UserWithdrawnEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SituationMembershipCleanupListenerTest {
    @Test
    void springRoutesBothActualAEventsSynchronously() {
        var service = mock(SituationMembershipCleanupService.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SituationMembershipCleanupService.class, () -> service);
            context.register(SituationMembershipCleanupListener.class);
            context.refresh();
            var now = LocalDateTime.of(2026, 11, 16, 12, 0);
            var relation = new RelationshipEndedEvent(1L, 10L, 20L, RelationshipType.GUARDIAN, now);
            var withdrawal = new UserWithdrawnEvent(20L, now);
            context.publishEvent(relation);
            context.publishEvent(withdrawal);
            verify(service).relationshipEnded(relation);
            verify(service).userWithdrawn(withdrawal);
            doThrow(new IllegalStateException("cleanup failed")).when(service).relationshipEnded(relation);
            assertThatThrownBy(() -> context.publishEvent(relation)).isInstanceOf(IllegalStateException.class);
        }
    }
}
