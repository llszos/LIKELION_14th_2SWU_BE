package com.gyeot.domain.situation.dto.internal;

import com.gyeot.domain.situation.entity.*;
import com.gyeot.domain.situation.service.SituationParticipantRemovalProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SituationParticipantRemovalResultTest {
    private final LocalDateTime time = LocalDateTime.of(2026, 11, 15, 10, 0, 0, 123456000);

    @Test
    void mapsActualRemovalResultUsingExistingProcessorWithoutFurtherMutation() {
        var participant = participant();
        var processor = new SituationParticipantRemovalProcessor(mock(ApplicationEventPublisher.class),
                Clock.fixed(Instant.parse("2026-11-15T01:00:00.123456789Z"), ZoneOffset.UTC));
        processor.removeValidated(3L, participant);

        var result = SituationParticipantRemovalResult.from(participant);

        assertThat(result.participantId()).isEqualTo(7L); // 대상 사용자 ID 20과 구분
        assertThat(result.status()).isEqualTo(SituationParticipantStatus.REMOVED);
        assertThat(result.leftAt()).isEqualTo(time);
        assertThat(participant.getStatus()).isEqualTo(result.status());
        assertThat(participant.getLeftAt()).isEqualTo(result.leftAt());
        assertThat(participant.getUserId()).isEqualTo(20L);
    }

    @Test
    void preservesLeftStatusRatherThanFabricatingRemovalSuccess() {
        var participant = participant();
        participant.leave(time);
        var result = SituationParticipantRemovalResult.from(participant);
        assertThat(result.participantId()).isEqualTo(7L);
        assertThat(result.status()).isEqualTo(SituationParticipantStatus.LEFT);
        assertThat(result.leftAt()).isEqualTo(time);
        assertThat(participant.getStatus()).isEqualTo(SituationParticipantStatus.LEFT);
    }

    @Test
    void preservesActiveStatusAndNullTimeWithoutChangingEntity() {
        var participant = participant();
        var result = SituationParticipantRemovalResult.from(participant);
        assertThat(result.status()).isEqualTo(SituationParticipantStatus.ACTIVE);
        assertThat(result.leftAt()).isNull();
        assertThat(participant.getStatus()).isEqualTo(SituationParticipantStatus.ACTIVE);
        assertThat(participant.getLeftAt()).isNull();
    }

    private SituationParticipant participant() {
        var situation = Situation.create(10L, "유형", "이름", LocalDateTime.of(2026, 11, 15, 0, 0), LocalDateTime.of(2026, 11, 21, 0, 0));
        ReflectionTestUtils.setField(situation, "id", 3L);
        var participant = SituationParticipant.create(situation, 20L, SituationParticipantRole.PARTICIPANT, time.minusDays(1));
        ReflectionTestUtils.setField(participant, "id", 7L);
        return participant;
    }
}
