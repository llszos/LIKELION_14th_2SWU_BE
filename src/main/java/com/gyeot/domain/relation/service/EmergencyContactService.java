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
import com.gyeot.domain.user.entity.AccountStatus;
import com.gyeot.domain.user.entity.User;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 보호자와 추가 비상연락 대상을 통합 연락 순서로 조회하고 변경합니다. */
@Service
@RequiredArgsConstructor
public class EmergencyContactService {

    private final EmergencyContactRepository emergencyContactRepository;
    private final RelationshipRepository relationshipRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public EmergencyContactListResponse getEmergencyContacts(Long userId) {
        validateAuthenticatedUser(userId);
        return buildListResponse(userId);
    }

    /** 현재 보호자와 추가 대상의 전체 목록을 모두 포함하는 경우에만 두 순서를 함께 변경합니다. */
    @Transactional
    public EmergencyContactOrderResponse changeOrder(Long userId, EmergencyContactOrderChangeRequest request) {
        lockActiveUser(userId);
        List<Long> guardianUserIds = request == null ? null : request.guardianUserIds();
        List<Long> emergencyContactUserIds = request == null ? null : request.emergencyContactUserIds();
        if (guardianUserIds == null || emergencyContactUserIds == null
                || guardianUserIds.stream().anyMatch(java.util.Objects::isNull)
                || emergencyContactUserIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }

        List<Long> allRequestedIds = new ArrayList<>(guardianUserIds);
        allRequestedIds.addAll(emergencyContactUserIds);
        if (new HashSet<>(allRequestedIds).size() != allRequestedIds.size()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }

        List<Relationship> guardians = findActiveGuardians(userId);
        List<EmergencyContact> contacts = emergencyContactRepository.findAllByUserIdOrderByPriorityAsc(userId);
        Set<Long> currentGuardianIds = guardians.stream()
                .map(Relationship::getTargetUserId)
                .collect(Collectors.toSet());
        Set<Long> currentEmergencyContactIds = contacts.stream()
                .map(EmergencyContact::getContactUserId)
                .collect(Collectors.toSet());
        if (currentGuardianIds.size() != guardians.size()
                || currentEmergencyContactIds.size() != contacts.size()
                || !currentGuardianIds.equals(new HashSet<>(guardianUserIds))
                || !currentEmergencyContactIds.equals(new HashSet<>(emergencyContactUserIds))) {
            throw new BusinessException(EmergencyContactErrorCode.CONTACT_ORDER_CHANGED);
        }

        List<Relationship> reorderedGuardians = guardianUserIds.stream()
                .map(targetId -> guardians.stream()
                        .filter(relationship -> relationship.getTargetUserId().equals(targetId))
                        .findFirst()
                        .orElseThrow(() -> new BusinessException(EmergencyContactErrorCode.CONTACT_ORDER_CHANGED)))
                .toList();
        List<EmergencyContact> reorderedContacts = emergencyContactUserIds.stream()
                .map(targetId -> contacts.stream()
                        .filter(contact -> contact.getContactUserId().equals(targetId))
                        .findFirst()
                        .orElseThrow(() -> new BusinessException(EmergencyContactErrorCode.CONTACT_ORDER_CHANGED)))
                .toList();

        reorderGuardians(reorderedGuardians);
        normalizeEmergencyContactPriorities(reorderedContacts);
        return buildOrderResponse(reorderedGuardians, reorderedContacts);
    }

    /** 나의 사람 관계가 생성될 때 관계 방향과 같은 방향의 추가 연락 대상을 만듭니다. */
    @Transactional
    public void addForMyPersonRelationship(Long userId, Long targetUserId) {
        List<EmergencyContact> contacts = emergencyContactRepository.findAllByUserIdOrderByPriorityAsc(userId);
        if (contacts.stream().anyMatch(contact -> contact.getContactUserId().equals(targetUserId))) {
            return;
        }
        normalizeEmergencyContactPriorities(contacts);
        emergencyContactRepository.save(EmergencyContact.create(userId, targetUserId, contacts.size() + 1));
    }

    /** 나의 사람 관계 해제 시 해당 방향만 제거하고 남은 사용자 지정 순서를 보존합니다. */
    @Transactional
    public void removeForMyPersonRelationship(Long userId, Long targetUserId) {
        List<EmergencyContact> contacts = emergencyContactRepository.findAllByUserIdOrderByPriorityAsc(userId);
        List<EmergencyContact> remaining = contacts.stream()
                .filter(contact -> !contact.getContactUserId().equals(targetUserId))
                .toList();
        if (remaining.size() == contacts.size()) {
            return;
        }
        emergencyContactRepository.deleteAll(contacts.stream()
                .filter(contact -> contact.getContactUserId().equals(targetUserId))
                .toList());
        emergencyContactRepository.flush();
        normalizeEmergencyContactPriorities(remaining);
    }

    private EmergencyContactListResponse buildListResponse(Long userId) {
        List<Relationship> guardians = findActiveGuardians(userId);
        List<EmergencyContact> contacts = emergencyContactRepository.findAllByUserIdOrderByPriorityAsc(userId);
        Map<Long, Relationship> myPeopleById = relationshipRepository
                .findAllBySourceUserIdAndRelationshipTypeAndStatus(
                        userId, RelationshipType.MY_PERSON, RelationshipStatus.ACTIVE)
                .stream()
                .collect(Collectors.toMap(Relationship::getTargetUserId, Function.identity(), (first, ignored) -> first));

        List<Long> targetIds = new ArrayList<>();
        guardians.forEach(guardian -> targetIds.add(guardian.getTargetUserId()));
        contacts.forEach(contact -> targetIds.add(contact.getContactUserId()));
        Map<Long, User> usersById = userRepository.findAllById(targetIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (first, ignored) -> first));

        List<EmergencyContactListResponse.Item> items = new ArrayList<>();
        int priority = 1;
        for (Relationship guardian : guardians) {
            User target = usersById.get(guardian.getTargetUserId());
            items.add(new EmergencyContactListResponse.Item(
                    guardian.getTargetUserId(), target == null ? null : target.getName(),
                    RelationshipType.GUARDIAN, guardian.getPersonalLabel(), priority++, guardian.getGuardianPriority()));
        }
        for (EmergencyContact contact : contacts) {
            Relationship relationship = myPeopleById.get(contact.getContactUserId());
            User target = usersById.get(contact.getContactUserId());
            items.add(new EmergencyContactListResponse.Item(
                    contact.getContactUserId(), target == null ? null : target.getName(),
                    RelationshipType.MY_PERSON, relationship == null ? null : relationship.getPersonalLabel(),
                    priority++, null));
        }
        return new EmergencyContactListResponse(items);
    }

    private EmergencyContactOrderResponse buildOrderResponse(
            List<Relationship> guardians,
            List<EmergencyContact> contacts
    ) {
        List<EmergencyContactOrderResponse.Item> items = new ArrayList<>();
        int priority = 1;
        for (Relationship guardian : guardians) {
            items.add(new EmergencyContactOrderResponse.Item(
                    guardian.getTargetUserId(), RelationshipType.GUARDIAN, priority++));
        }
        for (EmergencyContact contact : contacts) {
            items.add(new EmergencyContactOrderResponse.Item(
                    contact.getContactUserId(), RelationshipType.MY_PERSON, priority++));
        }
        return new EmergencyContactOrderResponse(items);
    }

    private List<Relationship> findActiveGuardians(Long userId) {
        return relationshipRepository.findAllBySourceUserIdAndRelationshipTypeAndStatusOrderByGuardianPriorityAsc(
                userId, RelationshipType.GUARDIAN, RelationshipStatus.ACTIVE);
    }

    private void reorderGuardians(List<Relationship> guardians) {
        if (guardians.isEmpty()) {
            return;
        }
        guardians.forEach(Relationship::clearGuardianPriorityForReorder);
        relationshipRepository.saveAllAndFlush(guardians);
        for (int index = 0; index < guardians.size(); index++) {
            guardians.get(index).changeGuardianPriority(index + 1);
        }
        relationshipRepository.saveAllAndFlush(guardians);
    }

    private void normalizeEmergencyContactPriorities(List<EmergencyContact> contacts) {
        if (contacts.isEmpty()) {
            return;
        }
        int temporaryPriorityStart = contacts.stream()
                .mapToInt(EmergencyContact::getPriority)
                .max()
                .orElse(0) + contacts.size() + 1;
        for (int index = 0; index < contacts.size(); index++) {
            contacts.get(index).changePriority(temporaryPriorityStart + index);
        }
        emergencyContactRepository.flush();
        for (int index = 0; index < contacts.size(); index++) {
            contacts.get(index).changePriority(index + 1);
        }
        emergencyContactRepository.flush();
    }

    private void validateAuthenticatedUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
    }

    private void lockActiveUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(EmergencyContactErrorCode.EMERGENCY_CONTACT_USER_NOT_AVAILABLE));
        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException(EmergencyContactErrorCode.EMERGENCY_CONTACT_USER_NOT_AVAILABLE);
        }
    }
}
