package com.gyeot.domain.relation.service;

import com.gyeot.domain.relation.dto.request.EmergencyContactOrderChangeRequest;
import com.gyeot.domain.relation.dto.response.EmergencyContactListResponse;
import com.gyeot.domain.relation.dto.response.EmergencyContactOrderResponse;
import com.gyeot.domain.relation.entity.EmergencyContact;
import com.gyeot.domain.relation.entity.Relationship;
import com.gyeot.domain.relation.entity.RelationshipStatus;
import com.gyeot.domain.relation.entity.RelationshipType;
import com.gyeot.domain.relation.exception.EmergencyContactErrorCode;
import com.gyeot.domain.relation.repository.EmergencyContactRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmergencyContactServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long GUARDIAN_ID = 2L;
    private static final Long FIRST_CONTACT_ID = 3L;
    private static final Long SECOND_CONTACT_ID = 4L;

    private EmergencyContactRepository emergencyContactRepository;
    private RelationshipRepository relationshipRepository;
    private UserRepository userRepository;
    private EmergencyContactService service;

    @BeforeEach
    void setUp() {
        emergencyContactRepository = mock(EmergencyContactRepository.class);
        relationshipRepository = mock(RelationshipRepository.class);
        userRepository = mock(UserRepository.class);
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
        service = new EmergencyContactService(emergencyContactRepository, relationshipRepository, userRepository);
    }

    @Test
    void 비상연락망은_보호자를_먼저_보이고_탈퇴한_사용자는_이름을_null로_반환한다() {
        Relationship guardian = Relationship.createGuardian(USER_ID, GUARDIAN_ID, 1);
        Relationship myPerson = Relationship.createMyPerson(USER_ID, FIRST_CONTACT_ID, "친구");
        EmergencyContact contact = EmergencyContact.create(USER_ID, FIRST_CONTACT_ID, 1);
        when(relationshipRepository.findAllBySourceUserIdAndRelationshipTypeAndStatusOrderByGuardianPriorityAsc(
                USER_ID, RelationshipType.GUARDIAN, RelationshipStatus.ACTIVE)).thenReturn(List.of(guardian));
        when(relationshipRepository.findAllBySourceUserIdAndRelationshipTypeAndStatus(
                USER_ID, RelationshipType.MY_PERSON, RelationshipStatus.ACTIVE)).thenReturn(List.of(myPerson));
        when(emergencyContactRepository.findAllByUserIdOrderByPriorityAsc(USER_ID)).thenReturn(List.of(contact));
        User withdrawn = user(FIRST_CONTACT_ID);
        withdrawn.withdraw(java.time.LocalDateTime.now());
        when(userRepository.findAllById(List.of(GUARDIAN_ID, FIRST_CONTACT_ID)))
                .thenReturn(List.of(user(GUARDIAN_ID), withdrawn));

        EmergencyContactListResponse response = service.getEmergencyContacts(USER_ID);

        assertEquals(2, response.items().size());
        assertEquals(GUARDIAN_ID, response.items().get(0).userId());
        assertEquals(RelationshipType.GUARDIAN, response.items().get(0).relationshipType());
        assertEquals(1, response.items().get(0).priority());
        assertEquals(FIRST_CONTACT_ID, response.items().get(1).userId());
        assertEquals(null, response.items().get(1).name());
        assertEquals("친구", response.items().get(1).personalLabel());
        assertEquals(2, response.items().get(1).priority());
        assertEquals(null, response.items().get(1).guardianPriority());
    }

    @Test
    void 나의_사람_관계_생성은_해당_방향의_비상연락_대상을_마지막에_자동_추가한다() {
        EmergencyContact existing = EmergencyContact.create(USER_ID, FIRST_CONTACT_ID, 1);
        when(emergencyContactRepository.findAllByUserIdOrderByPriorityAsc(USER_ID)).thenReturn(List.of(existing));

        service.addForMyPersonRelationship(USER_ID, SECOND_CONTACT_ID);

        verify(emergencyContactRepository).save(org.mockito.ArgumentMatchers.argThat(
                contact -> contact.getUserId().equals(USER_ID)
                        && contact.getContactUserId().equals(SECOND_CONTACT_ID)
                        && contact.getPriority() == 2));
    }

    @Test
    void 나의_사람_관계_해제는_해당_방향만_삭제하고_남은_순서를_정리한다() {
        EmergencyContact removed = EmergencyContact.create(USER_ID, FIRST_CONTACT_ID, 1);
        EmergencyContact remaining = EmergencyContact.create(USER_ID, SECOND_CONTACT_ID, 2);
        when(emergencyContactRepository.findAllByUserIdOrderByPriorityAsc(USER_ID))
                .thenReturn(List.of(removed, remaining));

        service.removeForMyPersonRelationship(USER_ID, FIRST_CONTACT_ID);

        verify(emergencyContactRepository).deleteAll(List.of(removed));
        assertEquals(1, remaining.getPriority());
    }

    @Test
    void 보호자와_추가_대상_순서를_같은_요청에서_변경하고_통합_우선순위를_반환한다() {
        Relationship firstGuardian = Relationship.createGuardian(USER_ID, GUARDIAN_ID, 1);
        Relationship secondGuardian = Relationship.createGuardian(USER_ID, 5L, 2);
        EmergencyContact firstContact = EmergencyContact.create(USER_ID, FIRST_CONTACT_ID, 1);
        EmergencyContact secondContact = EmergencyContact.create(USER_ID, SECOND_CONTACT_ID, 2);
        when(relationshipRepository.findAllBySourceUserIdAndRelationshipTypeAndStatusOrderByGuardianPriorityAsc(
                USER_ID, RelationshipType.GUARDIAN, RelationshipStatus.ACTIVE))
                .thenReturn(List.of(firstGuardian, secondGuardian));
        when(emergencyContactRepository.findAllByUserIdOrderByPriorityAsc(USER_ID))
                .thenReturn(List.of(firstContact, secondContact));

        EmergencyContactOrderResponse response = service.changeOrder(USER_ID,
                new EmergencyContactOrderChangeRequest(
                        List.of(5L, GUARDIAN_ID), List.of(SECOND_CONTACT_ID, FIRST_CONTACT_ID)));

        assertEquals(1, secondGuardian.getGuardianPriority());
        assertEquals(2, firstGuardian.getGuardianPriority());
        assertEquals(1, secondContact.getPriority());
        assertEquals(2, firstContact.getPriority());
        assertEquals(List.of(5L, GUARDIAN_ID, SECOND_CONTACT_ID, FIRST_CONTACT_ID),
                response.items().stream().map(EmergencyContactOrderResponse.Item::contactUserId).toList());
        assertEquals(List.of(1, 2, 3, 4),
                response.items().stream().map(EmergencyContactOrderResponse.Item::priority).toList());
        verify(relationshipRepository, times(2)).saveAllAndFlush(List.of(secondGuardian, firstGuardian));
    }

    @Test
    void 두_목록_사이의_중복_ID는_INVALID_INPUT으로_거부한다() {
        Relationship guardian = Relationship.createGuardian(USER_ID, GUARDIAN_ID, 1);
        when(relationshipRepository.findAllBySourceUserIdAndRelationshipTypeAndStatusOrderByGuardianPriorityAsc(
                USER_ID, RelationshipType.GUARDIAN, RelationshipStatus.ACTIVE)).thenReturn(List.of(guardian));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.changeOrder(USER_ID, new EmergencyContactOrderChangeRequest(
                        List.of(GUARDIAN_ID), List.of(GUARDIAN_ID))));

        assertEquals(CommonErrorCode.INVALID_INPUT, exception.getErrorCode());
        verify(emergencyContactRepository, never()).flush();
    }

    @Test
    void 관계_변경_뒤의_오래된_순서_요청은_CONTACT_ORDER_CHANGED다() {
        when(relationshipRepository.findAllBySourceUserIdAndRelationshipTypeAndStatusOrderByGuardianPriorityAsc(
                USER_ID, RelationshipType.GUARDIAN, RelationshipStatus.ACTIVE)).thenReturn(List.of());
        when(emergencyContactRepository.findAllByUserIdOrderByPriorityAsc(USER_ID)).thenReturn(List.of());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.changeOrder(USER_ID,
                        new EmergencyContactOrderChangeRequest(List.of(GUARDIAN_ID), List.of())));

        assertEquals(EmergencyContactErrorCode.CONTACT_ORDER_CHANGED, exception.getErrorCode());
        verify(emergencyContactRepository, never()).flush();
    }

    private User user(Long id) {
        User user = User.register("user" + id + "@example.com", "hash", "사용자 " + id);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
