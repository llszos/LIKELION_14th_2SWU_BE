package com.gyeot.domain.situation.query;

import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.entity.SituationParticipant;
import com.gyeot.domain.situation.entity.SituationParticipantRole;
import com.gyeot.domain.situation.entity.SituationParticipantStatus;
import com.gyeot.domain.situation.repository.SituationParticipantRepository;
import com.gyeot.domain.situation.repository.SituationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** DB 없이 입력 계약, 조회 위임 및 요약 매핑을 검증합니다. 상태 필터의 실제 동작은 통합 테스트에서 검증합니다. */
class SituationParticipantReaderImplTest {
    private static final Long SITUATION_ID = 1L;
    private static final Long USER_ID = 20L;
    private static final LocalDate START = LocalDate.of(2026, 11, 1);
    private static final LocalDate END = LocalDate.of(2026, 11, 5);
    private static final LocalDateTime JOINED_AT = START.atStartOfDay();

    private SituationRepository situationRepository;
    private SituationParticipantRepository participantRepository;
    private SituationParticipantReader reader;

    @BeforeEach
    void setUp() {
        situationRepository = mock(SituationRepository.class);
        participantRepository = mock(SituationParticipantRepository.class);
        reader = new SituationParticipantReaderImpl(situationRepository, participantRepository);
    }

    @Test
    void 참여_조회의_상황_ID가_null이면_조회하지_않는다() {
        assertThatThrownBy(() -> reader.isActiveParticipant(null, USER_ID))
                .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(situationRepository, participantRepository);
    }

    @Test
    void 참여_조회의_사용자_ID가_null이면_조회하지_않는다() {
        assertThatThrownBy(() -> reader.isActiveParticipant(SITUATION_ID, null))
                .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(situationRepository, participantRepository);
    }

    @Test
    void 상황_조회의_ID가_null이면_조회하지_않는다() {
        assertThatThrownBy(() -> reader.findSituation(null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(situationRepository, participantRepository);
    }

    @Test
    void ACTIVE_참여가_있으면_true이다() {
        givenParticipation(true);
        assertThat(reader.isActiveParticipant(SITUATION_ID, USER_ID)).isTrue();
        verifyNoInteractions(situationRepository);
    }

    @Test
    void LEFT_참여는_false이다() {
        SituationParticipant participant = participant();
        participant.leave(JOINED_AT.plusHours(1));
        givenParticipation(participant.getStatus() == SituationParticipantStatus.ACTIVE);
        assertThat(reader.isActiveParticipant(SITUATION_ID, USER_ID)).isFalse();
        verifyNoInteractions(situationRepository);
    }

    @Test
    void REMOVED_참여는_false이다() {
        SituationParticipant participant = participant();
        participant.remove(JOINED_AT.plusHours(1));
        givenParticipation(participant.getStatus() == SituationParticipantStatus.ACTIVE);
        assertThat(reader.isActiveParticipant(SITUATION_ID, USER_ID)).isFalse();
    }

    @Test
    void 참여가_없으면_false이다() {
        givenParticipation(false);
        assertThat(reader.isActiveParticipant(SITUATION_ID, USER_ID)).isFalse();
    }

    @Test
    void 상황_요약의_모든_필드를_매핑한다() {
        when(situationRepository.findById(SITUATION_ID)).thenReturn(Optional.of(situation()));
        assertThat(reader.findSituation(SITUATION_ID)).contains(
                new SituationParticipantReader.SituationSummary(10L, START, END, "ACTIVE"));
        verifyNoInteractions(participantRepository);
    }

    @Test
    void ENDED_상황도_반환하고_ACTIVE_참여는_별도로_true이다() {
        Situation situation = situation();
        situation.end(END.atTime(23, 0));
        when(situationRepository.findById(SITUATION_ID)).thenReturn(Optional.of(situation));
        givenParticipation(true);

        assertThat(reader.findSituation(SITUATION_ID)).contains(
                new SituationParticipantReader.SituationSummary(10L, START, END, "ENDED"));
        assertThat(reader.isActiveParticipant(SITUATION_ID, USER_ID)).isTrue();
        verify(situationRepository, times(1)).findById(SITUATION_ID);
    }

    @Test
    void 상황이_없으면_빈_Optional과_false이다() {
        when(situationRepository.findById(SITUATION_ID)).thenReturn(Optional.empty());
        givenParticipation(false);
        assertThat(reader.findSituation(SITUATION_ID)).isEmpty();
        assertThat(reader.isActiveParticipant(SITUATION_ID, USER_ID)).isFalse();
    }

    private void givenParticipation(boolean exists) {
        when(participantRepository.existsBySituation_IdAndUserIdAndStatus(
                SITUATION_ID, USER_ID, SituationParticipantStatus.ACTIVE)).thenReturn(exists);
    }

    private Situation situation() {
        return Situation.create(10L, "돌봄", "병원 동행", START, END);
    }

    private SituationParticipant participant() {
        return SituationParticipant.create(situation(), USER_ID, SituationParticipantRole.PARTICIPANT, JOINED_AT);
    }
}
