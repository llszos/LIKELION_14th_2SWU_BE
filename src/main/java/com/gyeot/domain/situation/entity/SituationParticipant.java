package com.gyeot.domain.situation.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 상황 참여자의 기본 구조입니다. 아직 DB에 저장할 수 있는 JPA Entity가 아닙니다.
 * SituationType 확정 후 Situation을 구현하고 JPA 매핑을 완성합니다.
 * created_at·updated_at 컬럼이 없으므로 BaseEntity를 상속하지 않습니다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SituationParticipant {

    // TODO: JPA 매핑 시 ERD의 PK와 연결합니다. 현재는 자동 발급되지 않습니다.
    private Long id;

    // TODO: Situation 구현 후 LAZY 단방향 @ManyToOne과 situation_id 매핑을 추가합니다.
    // 임시 situationId 컬럼이나 불완전한 Situation Entity는 만들지 않습니다.

    // 다른 도메인인 User는 객체가 아니라 ID로 참조합니다.
    private Long userId;

    private SituationParticipantRole role;
    private SituationParticipantStatus status;
    private LocalDateTime joinedAt;
    private LocalDateTime leftAt;

    /**
     * 확정된 참여 초기값만 설정합니다. 아직 상황 연결이나 DB 저장을 수행하지 않습니다.
     * TODO: Situation 연결 시 OWNER의 userId와 상황 creatorId 일치 검증을 추가합니다.
     */
    public static SituationParticipant create(Long userId, SituationParticipantRole role,
                                               LocalDateTime joinedAt) {
        SituationParticipant participant = new SituationParticipant();
        participant.userId = Objects.requireNonNull(userId, "참여 사용자 ID는 필수입니다.");
        participant.role = Objects.requireNonNull(role, "참여 역할은 필수입니다.");
        participant.joinedAt = Objects.requireNonNull(joinedAt, "참여 시각은 필수입니다.");
        participant.status = SituationParticipantStatus.ACTIVE;
        participant.leftAt = null;
        return participant;
    }

    // TODO: 이탈·제외 메서드는 OWNER 제한과 반복 호출 정책 확인 후 추가합니다.
    // TODO: 활성 참여 중복 차단용 생성 컬럼·UNIQUE의 실제 식은 DDL 확정 후 적용합니다.
}
