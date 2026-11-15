package com.gyeot.domain.relation.service;

import com.gyeot.domain.relation.entity.Relationship;
import com.gyeot.domain.relation.entity.RelationshipStatus;
import com.gyeot.domain.relation.entity.RelationshipType;
import com.gyeot.domain.relation.repository.RelationshipRepository;
import com.gyeot.domain.user.entity.User;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RelationshipServiceTest {

    private static final Long USER_ID = 1L;
    private RelationshipRepository relationshipRepository;
    private RelationshipService service;

    @BeforeEach
    void setUp() {
        relationshipRepository = mock(RelationshipRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        User user = User.register("user@example.com", "hash", "사용자");
        ReflectionTestUtils.setField(user, "id", USER_ID);
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
        service = new RelationshipService(relationshipRepository, userRepository);
    }

    @Test
    void 보호자_순서_변경은_요청한_순서대로_연속된_순위를_저장한다() {
        Relationship first = Relationship.createGuardian(USER_ID, 2L, 1);
        Relationship second = Relationship.createGuardian(USER_ID, 3L, 2);
        Relationship third = Relationship.createGuardian(USER_ID, 4L, 3);
        when(relationshipRepository.findAllBySourceUserIdAndRelationshipTypeAndStatusOrderByGuardianPriorityAsc(
                USER_ID, RelationshipType.GUARDIAN, RelationshipStatus.ACTIVE))
                .thenReturn(List.of(first, second, third));

        service.changeGuardianOrder(USER_ID, List.of(4L, 2L, 3L));

        assertEquals(1, third.getGuardianPriority());
        assertEquals(2, first.getGuardianPriority());
        assertEquals(3, second.getGuardianPriority());
        verify(relationshipRepository, org.mockito.Mockito.times(2)).saveAllAndFlush(List.of(first, second, third));
    }

    @Test
    void 현재_보호자와_정확히_일치하지_않는_순서_변경은_거부한다() {
        Relationship first = Relationship.createGuardian(USER_ID, 2L, 1);
        when(relationshipRepository.findAllBySourceUserIdAndRelationshipTypeAndStatusOrderByGuardianPriorityAsc(
                USER_ID, RelationshipType.GUARDIAN, RelationshipStatus.ACTIVE))
                .thenReturn(List.of(first));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.changeGuardianOrder(USER_ID, List.of(2L, 3L))
        );

        assertEquals(CommonErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void 중복_보호자나_다른_사용자의_ID가_포함된_순서_변경은_거부한다() {
        Relationship first = Relationship.createGuardian(USER_ID, 2L, 1);
        Relationship second = Relationship.createGuardian(USER_ID, 3L, 2);
        when(relationshipRepository.findAllBySourceUserIdAndRelationshipTypeAndStatusOrderByGuardianPriorityAsc(
                USER_ID, RelationshipType.GUARDIAN, RelationshipStatus.ACTIVE))
                .thenReturn(List.of(first, second));

        for (List<Long> invalidOrder : List.of(List.of(2L, 2L), List.of(2L, 99L))) {
            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> service.changeGuardianOrder(USER_ID, invalidOrder)
            );
            assertEquals(CommonErrorCode.INVALID_INPUT, exception.getErrorCode());
        }
    }
}
