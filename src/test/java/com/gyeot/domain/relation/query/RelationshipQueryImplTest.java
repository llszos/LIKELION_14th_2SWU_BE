package com.gyeot.domain.relation.query;

import com.gyeot.domain.relation.entity.Relationship;
import com.gyeot.domain.relation.entity.RelationshipStatus;
import com.gyeot.domain.relation.entity.RelationshipType;
import com.gyeot.domain.relation.repository.RelationshipQueryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RelationshipQueryImplTest {

    private RelationshipQueryRepository relationshipQueryRepository;
    private RelationshipQuery query;

    @BeforeEach
    void setUp() {
        relationshipQueryRepository = mock(RelationshipQueryRepository.class);
        query = new RelationshipQueryImpl(relationshipQueryRepository);
    }

    @Test
    void 대상_ID_목록에서_기준_사용자_방향의_활성_관계를_요약한다() {
        Relationship myPerson = Relationship.createMyPerson(1L, 2L, "친구");
        Relationship guardian = Relationship.createGuardian(1L, 3L, 1);
        when(relationshipQueryRepository.findAllBySourceUserIdAndTargetUserIdInAndStatus(
                1L, List.of(2L, 3L), RelationshipStatus.ACTIVE))
                .thenReturn(List.of(myPerson, guardian));

        List<RelationshipQuery.RelationshipSummary> result = query.findActiveRelationships(
                1L, Arrays.asList(2L, 3L, 2L, 1L, null));

        assertThat(result).containsExactly(
                new RelationshipQuery.RelationshipSummary(2L, RelationshipType.MY_PERSON, "친구"),
                new RelationshipQuery.RelationshipSummary(3L, RelationshipType.GUARDIAN, null));
        verify(relationshipQueryRepository).findAllBySourceUserIdAndTargetUserIdInAndStatus(
                1L, List.of(2L, 3L), RelationshipStatus.ACTIVE);
    }

    @Test
    void 대상_ID_목록이_null이거나_비어_있으면_빈_목록을_반환한다() {
        assertThat(query.findActiveRelationships(1L, null)).isEmpty();
        assertThat(query.findActiveRelationships(1L, List.of())).isEmpty();
        assertThat(query.findActiveRelationships(1L, Arrays.asList(null, 1L, 1L))).isEmpty();

        verifyNoInteractions(relationshipQueryRepository);
    }

    @Test
    void 기준_사용자_ID는_필수다() {
        assertThatNullPointerException()
                .isThrownBy(() -> query.findActiveRelationships(null, List.of(2L)));

        verifyNoInteractions(relationshipQueryRepository);
    }
}
