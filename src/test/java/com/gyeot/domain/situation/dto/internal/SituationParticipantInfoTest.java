package com.gyeot.domain.situation.dto.internal;

import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.entity.SituationParticipant;
import com.gyeot.domain.situation.entity.SituationParticipantRole;
import com.gyeot.domain.situation.entity.SituationParticipantStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SituationParticipantInfoTest {
    private static final LocalDateTime JOINED = LocalDateTime.of(2026, 11, 15, 9, 15, 30, 123456000);
    private static final LocalDateTime LEFT = JOINED.plusHours(2);
    private final Situation situation = Situation.create(10L, "유형", "이름",
            LocalDate.of(2026, 11, 15), LocalDate.of(2026, 11, 20));

    @Test
    void mapsActiveOwnerWithoutChangingEntity() {
        assertMapping(participant(10L, SituationParticipantRole.OWNER), SituationParticipantStatus.ACTIVE, null);
    }

    @Test
    void mapsActiveParticipantWithoutChangingEntity() {
        assertMapping(participant(20L, SituationParticipantRole.PARTICIPANT), SituationParticipantStatus.ACTIVE, null);
    }

    @Test
    void preservesLeftParticipationTimeWithoutChangingEntity() {
        var participant = participant(20L, SituationParticipantRole.PARTICIPANT);
        participant.leave(LEFT);
        assertMapping(participant, SituationParticipantStatus.LEFT, LEFT);
    }

    @Test
    void preservesRemovedParticipationTimeWithoutChangingEntity() {
        var participant = participant(20L, SituationParticipantRole.PARTICIPANT);
        participant.remove(LEFT);
        assertMapping(participant, SituationParticipantStatus.REMOVED, LEFT);
    }

    private SituationParticipant participant(Long userId, SituationParticipantRole role) {
        var participant = SituationParticipant.create(situation, userId, role, JOINED);
        ReflectionTestUtils.setField(participant, "id", 7L);
        return participant;
    }

    private void assertMapping(SituationParticipant participant, SituationParticipantStatus status,
                               LocalDateTime leftAt) {
        // 상태 전이 자체는 기존 Entity 테스트 범위이며 여기서는 매핑 전후 값을 비교합니다.
        Object[] before = values(participant);

        var info = SituationParticipantInfo.from(participant);

        assertThat(info.participantId()).isEqualTo(7L);
        assertThat(info.userId()).isEqualTo(participant.getUserId());
        assertThat(info.role()).isEqualTo(participant.getRole());
        assertThat(info.status()).isEqualTo(status);
        assertThat(info.joinedAt()).isEqualTo(JOINED);
        assertThat(info.leftAt()).isEqualTo(leftAt);
        assertThat(values(participant)).containsExactly(before);
        assertThat(participant.getSituation()).isSameAs(situation);
    }

    private Object[] values(SituationParticipant participant) {
        return new Object[]{participant.getId(), participant.getUserId(), participant.getRole(),
                participant.getStatus(), participant.getJoinedAt(), participant.getLeftAt()};
    }
}
