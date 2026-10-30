package com.gyeot.domain.relation.entity;

import com.gyeot.global.common.entity.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 사용자 간 나의 사람·상호 보호자 관계를 방향별로 저장합니다. (ERD 4.3 relationships)
 * 생성 일시는 BaseCreatedEntity가 자동으로 채웁니다.
 */
@Entity
@Table(
        name = "relationships",
        indexes = {
                @Index(name = "idx_relationships_source_type_status", columnList = "source_user_id, relationship_type, status"),
                @Index(name = "idx_relationships_target_type_status", columnList = "target_user_id, relationship_type, status")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Relationship extends BaseCreatedEntity {

    // 관계 ID
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 관계 등록자 ID (users.id). 다른 도메인이라 ID만 저장합니다.
    @Column(name = "source_user_id", nullable = false)
    private Long sourceUserId;

    // 관계 대상 사용자 ID (users.id)
    @Column(name = "target_user_id", nullable = false)
    private Long targetUserId;

    // 나의 사람 또는 보호자
    @Enumerated(EnumType.STRING)
    @Column(name = "relationship_type", nullable = false, length = 20)
    private RelationshipType relationshipType;

    // 사용자가 지정한 관계 표시명
    @Column(name = "personal_label", length = 50)
    private String personalLabel;

    // 활성 또는 종료 상태
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RelationshipStatus status;

    // 보호자 간 연락 순서. 활성 보호자만 사용자별 1~3을 사용하고 MY_PERSON은 NULL입니다.
    @Column(name = "guardian_priority")
    private Integer guardianPriority;

    // 관계 종료 시각. 활성 관계는 NULL입니다.
    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    private Relationship(
            Long sourceUserId,
            Long targetUserId,
            RelationshipType relationshipType,
            String personalLabel,
            Integer guardianPriority
    ) {
        this.sourceUserId = sourceUserId;
        this.targetUserId = targetUserId;
        this.relationshipType = relationshipType;
        this.personalLabel = personalLabel;
        this.guardianPriority = guardianPriority;
        this.status = RelationshipStatus.ACTIVE;
    }

    // 나의 사람 관계를 생성합니다.
    public static Relationship createMyPerson(Long sourceUserId, Long targetUserId, String personalLabel) {
        validateUsers(sourceUserId, targetUserId);
        return new Relationship(sourceUserId, targetUserId, RelationshipType.MY_PERSON, personalLabel, null);
    }

    // 보호자 관계의 한 방향 행을 생성합니다. 양방향 생성은 서비스에서 함께 처리합니다.
    public static Relationship createGuardian(Long sourceUserId, Long targetUserId, int guardianPriority) {
        validateUsers(sourceUserId, targetUserId);
        validateGuardianPriority(guardianPriority);
        return new Relationship(sourceUserId, targetUserId, RelationshipType.GUARDIAN, null, guardianPriority);
    }

    // 활성 보호자의 연락 순서를 변경합니다.
    public void changeGuardianPriority(int guardianPriority) {
        if (relationshipType != RelationshipType.GUARDIAN || status != RelationshipStatus.ACTIVE) {
            throw new IllegalStateException("활성 보호자 관계의 순서만 변경할 수 있습니다.");
        }

        validateGuardianPriority(guardianPriority);
        this.guardianPriority = guardianPriority;
    }

    // 관계를 종료합니다.
    public void end(LocalDateTime endedAt) {
        if (this.status == RelationshipStatus.ENDED) {
            throw new IllegalStateException("이미 종료된 관계입니다.");
        }
        if (endedAt == null) {
            throw new IllegalArgumentException("관계 종료 시각은 필수입니다.");
        }

        this.status = RelationshipStatus.ENDED;
        this.endedAt = endedAt;
        this.guardianPriority = null;
    }

    private static void validateUsers(Long sourceUserId, Long targetUserId) {
        if (sourceUserId == null || targetUserId == null) {
            throw new IllegalArgumentException("관계 대상 사용자 ID는 필수입니다.");
        }
        if (sourceUserId.equals(targetUserId)) {
            throw new IllegalArgumentException("자기 자신과는 관계를 맺을 수 없습니다.");
        }
    }

    private static void validateGuardianPriority(int guardianPriority) {
        if (guardianPriority < 1 || guardianPriority > 3) {
            throw new IllegalArgumentException("보호자 순위는 1부터 3까지 지정할 수 있습니다.");
        }
    }
}
