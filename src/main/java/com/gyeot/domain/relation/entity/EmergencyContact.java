package com.gyeot.domain.relation.entity;

import com.gyeot.global.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 보호자 다음 순서로 연락할 추가 비상연락 대상을 저장합니다. (ERD 4.13 emergency_contacts) */
@Entity
@Table(
        name = "emergency_contacts",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_emergency_contacts_user_contact",
                        columnNames = {"user_id", "contact_user_id"}
                ),
                @UniqueConstraint(
                        name = "uk_emergency_contacts_user_priority",
                        columnNames = {"user_id", "priority"}
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmergencyContact extends BaseEntity {

    // 비상연락 설정 ID
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 연락망 소유자 ID (users.id)
    @Column(name = "user_id", nullable = false)
    private Long userId;

    // 추가 연락 대상 ID (users.id)
    @Column(name = "contact_user_id", nullable = false)
    private Long contactUserId;

    // 보호자 다음의 추가 연락 순서. 1부터 시작.
    @Column(name = "priority", nullable = false)
    private Integer priority;

    private EmergencyContact(Long userId, Long contactUserId, Integer priority) {
        this.userId = userId;
        this.contactUserId = contactUserId;
        this.priority = priority;
    }

    // 추가 비상연락 대상을 등록함.
    public static EmergencyContact create(Long userId, Long contactUserId, Integer priority) {
        validateUsers(userId, contactUserId);
        validatePriority(priority);
        return new EmergencyContact(userId, contactUserId, priority);
    }

    // 추가 연락 순서를 변경함.
    public void changePriority(Integer priority) {
        validatePriority(priority);
        this.priority = priority;
    }

    private static void validateUsers(Long userId, Long contactUserId) {
        if (userId == null || contactUserId == null) {
            throw new IllegalArgumentException("연락망 소유자와 대상 사용자 ID는 필수입니다.");
        }
        if (userId.equals(contactUserId)) {
            throw new IllegalArgumentException("자기 자신은 비상연락 대상에 등록할 수 없습니다.");
        }
    }

    private static void validatePriority(Integer priority) {
        if (priority == null || priority < 1) {
            throw new IllegalArgumentException("연락 순서는 1 이상이어야 합니다.");
        }
    }
}
