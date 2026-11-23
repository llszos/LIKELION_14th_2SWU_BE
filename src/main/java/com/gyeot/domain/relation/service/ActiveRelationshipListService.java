package com.gyeot.domain.relation.service;

import com.gyeot.domain.relation.dto.response.ActiveRelationshipListResponse;
import com.gyeot.domain.relation.entity.EmergencyContact;
import com.gyeot.domain.relation.entity.Relationship;
import com.gyeot.domain.relation.entity.RelationshipStatus;
import com.gyeot.domain.relation.entity.RelationshipType;
import com.gyeot.domain.relation.repository.EmergencyContactRepository;
import com.gyeot.domain.relation.repository.RelationshipRepository;
import com.gyeot.domain.user.query.UserProfileQuery;
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

/** 로그인 사용자가 등록한 활성 관계를 연락 순서에 맞춰 조회합니다. */
@Service
@RequiredArgsConstructor
public class ActiveRelationshipListService {

    private final RelationshipRepository relationshipRepository;
    private final EmergencyContactRepository emergencyContactRepository;
    private final UserProfileQuery userProfileQuery;

    @Transactional(readOnly = true)
    public ActiveRelationshipListResponse getActiveRelationships(Long userId) {
        if (userId == null) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }

        List<Relationship> guardians = relationshipRepository
                .findAllBySourceUserIdAndRelationshipTypeAndStatusOrderByGuardianPriorityAsc(
                        userId, RelationshipType.GUARDIAN, RelationshipStatus.ACTIVE);
        List<Relationship> myPeople = relationshipRepository
                .findAllBySourceUserIdAndRelationshipTypeAndStatus(
                        userId, RelationshipType.MY_PERSON, RelationshipStatus.ACTIVE);
        List<EmergencyContact> emergencyContacts = emergencyContactRepository
                .findAllByUserIdOrderByPriorityAsc(userId);

        List<Relationship> orderedMyPeople = orderMyPeopleByEmergencyContactPriority(myPeople, emergencyContacts);
        List<Long> targetIds = new ArrayList<>(guardians.size() + orderedMyPeople.size());
        guardians.forEach(relationship -> targetIds.add(relationship.getTargetUserId()));
        orderedMyPeople.forEach(relationship -> targetIds.add(relationship.getTargetUserId()));
        Map<Long, UserProfileQuery.UserProfile> profiles = userProfileQuery.findProfilesByUserIds(targetIds);

        List<ActiveRelationshipListResponse.Item> items = new ArrayList<>(targetIds.size());
        int priority = 1;
        for (Relationship guardian : guardians) {
            items.add(toItem(guardian, profiles, priority++));
        }
        for (Relationship myPerson : orderedMyPeople) {
            items.add(toItem(myPerson, profiles, priority++));
        }
        return new ActiveRelationshipListResponse(List.copyOf(items));
    }

    private List<Relationship> orderMyPeopleByEmergencyContactPriority(
            List<Relationship> myPeople,
            List<EmergencyContact> emergencyContacts
    ) {
        Map<Long, Relationship> relationshipByTargetId = myPeople.stream()
                .collect(Collectors.toMap(Relationship::getTargetUserId, Function.identity(), (first, ignored) -> first));
        Set<Long> addedTargetIds = new HashSet<>();
        List<Relationship> ordered = new ArrayList<>(myPeople.size());

        for (EmergencyContact contact : emergencyContacts) {
            Relationship relationship = relationshipByTargetId.get(contact.getContactUserId());
            if (relationship != null && addedTargetIds.add(relationship.getTargetUserId())) {
                ordered.add(relationship);
            }
        }

        // 연락 항목이 누락된 활성 관계도 응답에서 빠지지 않도록 마지막에 관계 ID 순으로 덧붙입니다.
        myPeople.stream()
                .filter(relationship -> addedTargetIds.add(relationship.getTargetUserId()))
                .sorted(java.util.Comparator.comparing(Relationship::getId))
                .forEach(ordered::add);
        return ordered;
    }

    private ActiveRelationshipListResponse.Item toItem(
            Relationship relationship,
            Map<Long, UserProfileQuery.UserProfile> profiles,
            int priority
    ) {
        UserProfileQuery.UserProfile profile = profiles.get(relationship.getTargetUserId());
        return new ActiveRelationshipListResponse.Item(
                relationship.getId(),
                relationship.getTargetUserId(),
                profile == null ? null : profile.name(),
                relationship.getRelationshipType(),
                relationship.getPersonalLabel(),
                priority,
                relationship.getRelationshipType() == RelationshipType.GUARDIAN
                        ? relationship.getGuardianPriority() : null
        );
    }
}
