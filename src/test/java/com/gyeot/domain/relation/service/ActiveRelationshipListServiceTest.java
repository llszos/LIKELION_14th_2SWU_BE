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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActiveRelationshipListServiceTest {

    private static final Long USER_ID = 1L;

    private RelationshipRepository relationshipRepository;
    private EmergencyContactRepository emergencyContactRepository;
    private UserProfileQuery userProfileQuery;
    private ActiveRelationshipListService service;
    private final AtomicInteger repositoryCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        repositoryCalls.set(0);
        relationshipRepository = stub(RelationshipRepository.class, Map.of(), repositoryCalls);
        emergencyContactRepository = stub(EmergencyContactRepository.class, Map.of(), repositoryCalls);
        userProfileQuery = stub(UserProfileQuery.class, Map.of(), repositoryCalls);
        service = new ActiveRelationshipListService(
                relationshipRepository, emergencyContactRepository, userProfileQuery);
    }

    @Test
    void 보호자를_먼저_보호자순으로_나의사람은_비상연락순으로_반환하고_연락항목이_없는_관계는_뒤에_붙인다() {
        Relationship firstGuardian = Relationship.createGuardian(USER_ID, 10L, 1);
        Relationship secondGuardian = Relationship.createGuardian(USER_ID, 11L, 2);
        Relationship firstMyPerson = myPerson(102L, 20L, "친구");
        Relationship secondMyPerson = myPerson(100L, 21L, "이웃");
        Relationship withoutContactEntry = myPerson(101L, 22L, "동료");

        relationshipRepository = stub(RelationshipRepository.class, Map.of(
                "findAllBySourceUserIdAndRelationshipTypeAndStatusOrderByGuardianPriorityAsc",
                List.of(firstGuardian, secondGuardian),
                "findAllBySourceUserIdAndRelationshipTypeAndStatus",
                List.of(firstMyPerson, withoutContactEntry, secondMyPerson)), repositoryCalls);
        emergencyContactRepository = stub(EmergencyContactRepository.class, Map.of(
                "findAllByUserIdOrderByPriorityAsc",
                List.of(EmergencyContact.create(USER_ID, 21L, 1), EmergencyContact.create(USER_ID, 20L, 2))),
                repositoryCalls);
        userProfileQuery = stub(UserProfileQuery.class, Map.of(
                "findProfilesByUserIds", Map.of(
                        10L, new UserProfileQuery.UserProfile(10L, "보호자1", false),
                        11L, new UserProfileQuery.UserProfile(11L, "보호자2", false),
                        21L, new UserProfileQuery.UserProfile(21L, "나의사람1", false),
                        20L, new UserProfileQuery.UserProfile(20L, "나의사람2", false),
                        22L, new UserProfileQuery.UserProfile(22L, "나의사람3", false))), repositoryCalls);
        service = new ActiveRelationshipListService(
                relationshipRepository, emergencyContactRepository, userProfileQuery);

        ActiveRelationshipListResponse response = service.getActiveRelationships(USER_ID);

        assertThat(response.items())
                .extracting(ActiveRelationshipListResponse.Item::userId)
                .containsExactly(10L, 11L, 21L, 20L, 22L);
        assertThat(response.items())
                .extracting(ActiveRelationshipListResponse.Item::priority)
                .containsExactly(1, 2, 3, 4, 5);
        assertThat(response.items())
                .extracting(ActiveRelationshipListResponse.Item::guardianPriority)
                .containsExactly(1, 2, null, null, null);
        assertThat(response.items().get(2).personalLabel()).isEqualTo("이웃");
        assertThat(response.items().get(3).personalLabel()).isEqualTo("친구");
    }

    @Test
    void 탈퇴했거나_프로필을_찾을_수_없는_관계는_이름을_null로_반환한다() {
        Relationship withdrawnPerson = Relationship.createMyPerson(USER_ID, 30L, "지인");
        Relationship missingProfilePerson = Relationship.createMyPerson(USER_ID, 31L, "가족");
        relationshipRepository = stub(RelationshipRepository.class, Map.of(
                "findAllBySourceUserIdAndRelationshipTypeAndStatusOrderByGuardianPriorityAsc", List.of(),
                "findAllBySourceUserIdAndRelationshipTypeAndStatus", List.of(withdrawnPerson, missingProfilePerson)),
                repositoryCalls);
        emergencyContactRepository = stub(EmergencyContactRepository.class, Map.of(
                "findAllByUserIdOrderByPriorityAsc", List.of(
                        EmergencyContact.create(USER_ID, 30L, 1),
                        EmergencyContact.create(USER_ID, 31L, 2))), repositoryCalls);
        userProfileQuery = stub(UserProfileQuery.class, Map.of(
                "findProfilesByUserIds", Map.of(30L, new UserProfileQuery.UserProfile(30L, null, true))),
                repositoryCalls);
        service = new ActiveRelationshipListService(
                relationshipRepository, emergencyContactRepository, userProfileQuery);

        ActiveRelationshipListResponse response = service.getActiveRelationships(USER_ID);

        assertThat(response.items()).hasSize(2);
        assertThat(response.items()).extracting(ActiveRelationshipListResponse.Item::name)
                .containsExactly(null, null);
    }

    @Test
    void 로그인_사용자_ID가_null이면_저장소를_조회하지_않는다() {
        assertThatThrownBy(() -> service.getActiveRelationships(null))
                .isInstanceOf(BusinessException.class);

        assertThat(repositoryCalls).hasValue(0);
    }

    private Relationship myPerson(Long id, Long targetUserId, String personalLabel) {
        Relationship relationship = Relationship.createMyPerson(USER_ID, targetUserId, personalLabel);
        ReflectionTestUtils.setField(relationship, "id", id);
        return relationship;
    }

    @SuppressWarnings("unchecked")
    private <T> T stub(Class<T> type, Map<String, ?> results, AtomicInteger calls) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> type.getSimpleName() + "Stub";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
            calls.incrementAndGet();
            if (!results.containsKey(method.getName())) {
                throw new UnsupportedOperationException("테스트 스텁에 없는 호출: " + method.getName());
            }
            return results.get(method.getName());
        });
    }
}
