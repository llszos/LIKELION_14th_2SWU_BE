package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.relation.entity.RelationshipStatus;
import com.gyeot.domain.relation.repository.RelationshipQueryRepository;
import com.gyeot.domain.situation.query.SituationParticipantReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** B(상황 참여)·A(관계) 조회를 Mock으로 바꿔 대상 자격 규칙만 확인합니다. */
public class RequestTargetEligibilityTest {
    private static final Long SITUATION_ID = 1L;
    private static final Long REQUESTER_ID = 10L;
    private static final Long TARGET_ID = 20L;

    private SituationParticipantReader participantReader;
    private RelationshipQueryRepository relationshipRepository;
    private RequestTargetEligibility eligibility;

    @BeforeEach
    void setUp() {
        participantReader = mock(SituationParticipantReader.class);
        relationshipRepository = mock(RelationshipQueryRepository.class);
        eligibility = new RequestTargetEligibility(participantReader, relationshipRepository);
    }

    @Test
    void 참여자이고_관계가_있으면_대상이_될_수_있다() {
        givenParticipant(true);
        givenRelationship(true);

        assertThat(eligibility.isEligible(SITUATION_ID, REQUESTER_ID, TARGET_ID)).isTrue();
    }

    @Test
    void 본인에게는_요청할_수_없다() {
        assertThat(eligibility.isEligible(SITUATION_ID, REQUESTER_ID, REQUESTER_ID)).isFalse();
    }

    @Test
    void 대상이_없으면_요청할_수_없다() {
        assertThat(eligibility.isEligible(SITUATION_ID, REQUESTER_ID, null)).isFalse();
    }

    @Test
    void 상황_참여자가_아니면_대상이_될_수_없다() {
        givenParticipant(false);
        givenRelationship(true);

        assertThat(eligibility.isEligible(SITUATION_ID, REQUESTER_ID, TARGET_ID)).isFalse();
    }

    @Test
    void 관계가_없으면_대상이_될_수_없다() {
        givenParticipant(true);
        givenRelationship(false);

        assertThat(eligibility.isEligible(SITUATION_ID, REQUESTER_ID, TARGET_ID)).isFalse();
    }

    private void givenParticipant(boolean active) {
        when(participantReader.isActiveParticipant(SITUATION_ID, TARGET_ID)).thenReturn(active);
    }

    private void givenRelationship(boolean exists) {
        when(relationshipRepository.existsBySourceUserIdAndTargetUserIdAndStatus(
                REQUESTER_ID, TARGET_ID, RelationshipStatus.ACTIVE)).thenReturn(exists);
    }
}