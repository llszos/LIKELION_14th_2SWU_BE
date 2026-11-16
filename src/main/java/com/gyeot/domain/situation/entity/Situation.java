package com.gyeot.domain.situation.entity;

import com.gyeot.global.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** ERD 4.4 situations. 생성·수정 시각은 BaseEntity가 관리합니다. */
@Entity
@Table(name = "situations", indexes = {
        @Index(name = "idx_situations_creator_id_status", columnList = "creator_id, status")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Situation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    // 다른 도메인의 User는 ID로만 참조합니다.
    @Column(name = "creator_id", nullable = false)
    private Long creatorId;

    // TODO: SituationType 허용값 확정 후 enum과 EnumType.STRING 매핑으로 전환합니다.
    // 임의의 유형·기본값은 지정하지 않습니다. 현재는 필수·길이만 검증합니다.
    @Column(name = "situation_type", nullable = false, length = 30)
    private String situationType;

    @Column(name = "title", nullable = false, length = 150)
    private String title;

    @Column(name = "start_at", nullable = false)
    // 오프셋 없는 Asia/Seoul 현지 시각입니다. endAt은 예정 종료, endedAt은 실제 처리 시각입니다.
    private LocalDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private LocalDateTime endAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SituationStatus status;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    /** 상황만 생성합니다. OWNER 참여 행의 동시 저장은 후속 Service의 트랜잭션 책임입니다. */
    public static Situation create(Long creatorId, String situationType, String title,
                                   LocalDateTime startAt, LocalDateTime endAt) {
        if (creatorId == null || startAt == null || endAt == null) {
            throw new IllegalArgumentException("생성자 ID와 상황 시작·종료 예정 시각은 필수입니다.");
        }
        validateText(situationType, 30, "상황 유형");
        validateText(title, 150, "상황 이름");
        if (!startAt.isBefore(endAt)) {
            throw new IllegalArgumentException("상황 시작 시각은 종료 예정 시각보다 빨라야 합니다.");
        }
        Situation situation = new Situation();
        situation.creatorId = creatorId;
        situation.situationType = situationType;
        situation.title = title;
        situation.startAt = startAt;
        situation.endAt = endAt;
        situation.status = SituationStatus.ACTIVE;
        situation.endedAt = null;
        return situation;
    }

    /**
     * 상황을 종료합니다. 종료 시각은 필수이며 호출자가 한국 시간 기준으로 전달합니다.
     * 이미 ENDED이면 아무것도 변경하지 않아 최초 종료 시각을 보존합니다.
     * 요청 취소·권한 회수와 이벤트 발행은 이 메서드에서 수행하지 않습니다.
     */
    public void end(LocalDateTime endedAt) {
        if (endedAt == null) {
            throw new IllegalArgumentException("종료 시각은 필수입니다.");
        }
        if (status == SituationStatus.ENDED) {
            return;
        }
        this.status = SituationStatus.ENDED;
        this.endedAt = endedAt;
    }

    /**
     * 기존 날짜 계약을 위한 임시 생성 어댑터. 종료 날짜는 포함입니다.
     * #116 마이그레이션과 같이 서울 현지 시각의 [시작일 00:00, 종료일 다음 날 00:00)으로 변환합니다.
     * 날짜 기반 호출부 전환 후 제거하며 새 코드는 LocalDateTime 오버로드를 사용합니다.
     */
    @Deprecated
    public static Situation create(Long creatorId, String situationType, String title,
                                   java.time.LocalDate startDate, java.time.LocalDate endDate) {
        if (startDate == null || endDate == null || startDate.isAfter(endDate)
                || endDate.equals(java.time.LocalDate.MAX)) {
            throw new IllegalArgumentException("상황 시작·종료 날짜와 변환 가능한 포함 종료일이 필요합니다.");
        }
        return create(creatorId, situationType, title,
                startDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay());
    }

    /**
     * 기존 Reader의 한국 날짜 요약 전용입니다. 시각 기반 자격 검증에는 getStartAt()을 사용합니다.
     * 저장된 LocalDateTime은 이미 서울 현지 시각이므로 별도의 UTC 변환을 하지 않습니다.
     */
    @Deprecated
    public java.time.LocalDate getStartDate() {
        return startAt.toLocalDate();
    }

    /**
     * 배타적 endAt 직전의 마지막 포함 날짜입니다. 자정 종료 시 다음 날을 포함하지 않습니다.
     * 날짜 요약은 시각 정밀도를 잃으므로 실제 종료 판정에는 getEndAt()을 사용합니다.
     */
    @Deprecated
    public java.time.LocalDate getEndDate() {
        return endAt.minusNanos(1).toLocalDate();
    }

    private static void validateText(String value, int maxLength, String name) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > maxLength) {
            throw new IllegalArgumentException(name + "은 비어 있지 않은 " + maxLength + "자 이내 값이어야 합니다.");
        }
    }
}
