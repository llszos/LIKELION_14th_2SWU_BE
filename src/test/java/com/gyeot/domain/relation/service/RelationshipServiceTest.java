package com.gyeot.domain.relation.service;

import com.gyeot.domain.relation.entity.Relationship;
import com.gyeot.domain.relation.entity.RelationshipStatus;
import com.gyeot.domain.relation.entity.RelationshipType;
import com.gyeot.domain.relation.event.RelationshipEndedEvent;
import com.gyeot.domain.relation.exception.RelationshipErrorCode;
import com.gyeot.domain.relation.repository.RelationshipRepository;
import com.gyeot.domain.user.entity.User;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RelationshipServiceTest {

    private static final Long USER_ID = 1L;
    private RelationshipRepository relationshipRepository;
    private UserRepository userRepository;
    private EmergencyContactService emergencyContactService;
    private ApplicationEventPublisher eventPublisher;
    private RelationshipService service;

    @BeforeEach
    void setUp() {
        relationshipRepository = mock(RelationshipRepository.class);
        userRepository = mock(UserRepository.class);
        emergencyContactService = mock(EmergencyContactService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user(1L)));
        when(userRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(user(2L)));
        Clock clock = Clock.fixed(Instant.parse("2026-11-07T12:30:00Z"), ZoneId.of("Asia/Seoul"));
        service = new RelationshipService(
                relationshipRepository, userRepository, emergencyContactService, eventPublisher, clock);
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

    @Test
    void 나의_사람_해제는_요청한_방향과_비상연락만_종료하고_이벤트를_한번_발행한다() {
        Relationship requested = relationship(10L, Relationship.createMyPerson(1L, 2L, "친구"));
        Relationship reverse = relationship(11L, Relationship.createMyPerson(2L, 1L, "지인"));
        when(relationshipRepository.findParticipantsById(10L))
                .thenReturn(Optional.of(new com.gyeot.domain.relation.repository.RelationshipParticipants(1L, 2L)));
        when(relationshipRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(requested));

        var response = service.endRelationship(1L, 10L);

        assertEquals(RelationshipStatus.ENDED, requested.getStatus());
        assertEquals(RelationshipStatus.ACTIVE, reverse.getStatus());
        assertEquals(LocalDateTime.of(2026, 11, 7, 21, 30), requested.getEndedAt());
        assertEquals(10L, response.relationshipId());
        assertEquals(RelationshipType.MY_PERSON, response.relationshipType());
        assertEquals(requested.getEndedAt(), response.endedAt());
        verify(emergencyContactService).removeForMyPersonRelationship(1L, 2L);
        verify(emergencyContactService, never()).removeForMyPersonRelationship(2L, 1L);
        verify(eventPublisher).publishEvent(new RelationshipEndedEvent(
                10L, 1L, 2L, RelationshipType.MY_PERSON, requested.getEndedAt()));
    }

    @Test
    void 보호자_해제는_양방향을_종료하고_각_사용자의_남은_순위를_정리한_뒤_이벤트를_한번_발행한다() {
        Relationship requested = relationship(20L, Relationship.createGuardian(1L, 2L, 1));
        Relationship reverse = relationship(21L, Relationship.createGuardian(2L, 1L, 2));
        Relationship remainingForFirst = Relationship.createGuardian(1L, 3L, 2);
        Relationship remainingForSecond = Relationship.createGuardian(2L, 4L, 1);
        when(relationshipRepository.findParticipantsById(20L))
                .thenReturn(Optional.of(new com.gyeot.domain.relation.repository.RelationshipParticipants(1L, 2L)));
        when(relationshipRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(requested));
        when(relationshipRepository.findActiveBetweenUsersForUpdate(1L, 2L, RelationshipStatus.ACTIVE))
                .thenReturn(List.of(requested, reverse));
        when(relationshipRepository.findAllBySourceUserIdAndRelationshipTypeAndStatusOrderByGuardianPriorityAsc(
                1L, RelationshipType.GUARDIAN, RelationshipStatus.ACTIVE)).thenReturn(List.of(remainingForFirst));
        when(relationshipRepository.findAllBySourceUserIdAndRelationshipTypeAndStatusOrderByGuardianPriorityAsc(
                2L, RelationshipType.GUARDIAN, RelationshipStatus.ACTIVE)).thenReturn(List.of(remainingForSecond));

        var response = service.endRelationship(1L, 20L);

        assertEquals(RelationshipStatus.ENDED, requested.getStatus());
        assertEquals(RelationshipStatus.ENDED, reverse.getStatus());
        assertEquals(requested.getEndedAt(), reverse.getEndedAt());
        assertEquals(1, remainingForFirst.getGuardianPriority());
        assertEquals(1, remainingForSecond.getGuardianPriority());
        assertEquals(RelationshipType.GUARDIAN, response.relationshipType());
        verify(relationshipRepository).saveAllAndFlush(List.of(requested, reverse));
        verify(eventPublisher, times(1)).publishEvent(new RelationshipEndedEvent(
                20L, 1L, 2L, RelationshipType.GUARDIAN, requested.getEndedAt()));
        verify(emergencyContactService, never()).removeForMyPersonRelationship(1L, 2L);
        verify(emergencyContactService, never()).removeForMyPersonRelationship(2L, 1L);
    }

    @Test
    void 이미_종료된_관계는_409로_응답하고_이벤트를_다시_발행하지_않는다() {
        Relationship ended = relationship(30L, Relationship.createMyPerson(1L, 2L, null));
        ended.end(LocalDateTime.of(2026, 11, 1, 10, 0));
        when(relationshipRepository.findParticipantsById(30L))
                .thenReturn(Optional.of(new com.gyeot.domain.relation.repository.RelationshipParticipants(1L, 2L)));
        when(relationshipRepository.findByIdForUpdate(30L)).thenReturn(Optional.of(ended));

        BusinessException exception = assertThrows(
                BusinessException.class, () -> service.endRelationship(1L, 30L));

        assertEquals(RelationshipErrorCode.RELATIONSHIP_ALREADY_ENDED, exception.getErrorCode());
        verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.<Object>any());
        verify(emergencyContactService, never()).removeForMyPersonRelationship(1L, 2L);
    }

    @Test
    void 다른_사용자가_등록한_방향의_관계는_존재하지_않는_관계처럼_거부한다() {
        Relationship relationship = relationship(40L, Relationship.createMyPerson(2L, 1L, null));
        when(relationshipRepository.findParticipantsById(40L))
                .thenReturn(Optional.of(new com.gyeot.domain.relation.repository.RelationshipParticipants(2L, 1L)));

        BusinessException exception = assertThrows(
                BusinessException.class, () -> service.endRelationship(1L, 40L));

        assertEquals(RelationshipErrorCode.RELATIONSHIP_NOT_FOUND, exception.getErrorCode());
        verify(relationshipRepository, never()).findByIdForUpdate(40L);
        verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.<Object>any());
    }

    private User user(Long id) {
        User user = User.register("user" + id + "@example.com", "hash", "사용자 " + id);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private Relationship relationship(Long id, Relationship relationship) {
        ReflectionTestUtils.setField(relationship, "id", id);
        return relationship;
    }
}
