package com.gyeot.domain.situation.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ERD 4.5 situation_participants. created_at·updated_at이 없어 공통 Entity를 상속하지 않습니다.
 * 활성 참여 중복 차단용 생성 컬럼·UNIQUE의 실제 식은 DDL 확정 후 적용합니다.
 */
@Entity
@Table(name = "situation_participants", indexes = {
        @Index(name = "idx_situation_participants_situation_id_status", columnList = "situation_id, status"),
        @Index(name = "idx_situation_participants_user_id_status", columnList = "user_id, status")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SituationParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "situation_id", nullable = false, updatable = false)
    private Situation situation;

    // 다른 도메인의 User는 ID로만 참조합니다.
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20, updatable = false)
    private SituationParticipantRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SituationParticipantStatus status;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private LocalDateTime joinedAt;

    @Column(name = "left_at")
    private LocalDateTime leftAt;

    /** 참여를 생성합니다. OWNER는 상황 생성자와 일치해야 합니다. 중복 참여 검증은 저장 계층의 책임입니다. */
    public static SituationParticipant create(Situation situation, Long userId,
                                              SituationParticipantRole role, LocalDateTime joinedAt) {
        if (situation == null || userId == null || role == null || joinedAt == null) {
            throw new IllegalArgumentException("상황·참여 사용자 ID·역할·참여 시각은 필수입니다.");
        }
        if (role == SituationParticipantRole.OWNER && !userId.equals(situation.getCreatorId())) {
            throw new IllegalArgumentException("OWNER는 상황 생성자와 같은 사용자여야 합니다.");
        }
        SituationParticipant participant = new SituationParticipant();
        participant.situation = situation;
        participant.userId = userId;
        participant.role = role;
        participant.joinedAt = joinedAt;
        participant.status = SituationParticipantStatus.ACTIVE;
        participant.leftAt = null;
        return participant;
    }

    /** 일반 참여자 자진 이탈: ACTIVE이면 LEFT와 시각을 기록합니다. OWNER는 이탈할 수 없습니다. */
    public void leave(LocalDateTime leftAt) {
        endParticipation(SituationParticipantStatus.LEFT, leftAt);
    }

    /** 일반 참여자 제외: ACTIVE이면 REMOVED와 시각을 기록합니다. OWNER는 제외할 수 없습니다. */
    public void remove(LocalDateTime leftAt) {
        endParticipation(SituationParticipantStatus.REMOVED, leftAt);
    }

    /**
     * 처리 시각은 반복 호출에서도 필수입니다. LEFT↔REMOVED 변경이나 재활성화는 하지 않습니다.
     * OWNER 참여 종료는 엔티티에서 차단합니다. 수행자 권한·관계 자격은 Service에서 검증합니다.
     * 이벤트 원인과 DB 참여 상태를 혼합하지 않으며 요청·권한 데이터는 변경하지 않습니다.
     */
    private void endParticipation(SituationParticipantStatus endStatus, LocalDateTime leftAt) {
        if (leftAt == null) {
            throw new IllegalArgumentException("참여 종료 시각은 필수입니다.");
        }
        if (role == SituationParticipantRole.OWNER) {
            throw new IllegalStateException("상황 생성자는 참여를 중단하거나 제외될 수 없습니다.");
        }
        if (status != SituationParticipantStatus.ACTIVE) {
            return;
        }
        this.status = endStatus;
        this.leftAt = leftAt;
    }
}
