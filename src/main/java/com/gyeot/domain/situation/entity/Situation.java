package com.gyeot.domain.situation.entity;

import com.gyeot.global.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** ERD 4.4 situations. 생성·수정 시각은 BaseEntity가 관리합니다. */
@Entity
@Table(name = "situations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Situation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", columnDefinition = "BIGINT UNSIGNED")
    private Long id;

    // 다른 도메인의 User는 ID로만 참조합니다.
    @Column(name = "creator_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long creatorId;

    // TODO: SituationType 허용값 확정 후 enum과 EnumType.STRING 매핑으로 전환합니다.
    // 임의의 유형·기본값은 지정하지 않습니다. 현재는 필수·길이만 검증합니다.
    @Column(name = "situation_type", nullable = false, length = 30)
    private String situationType;

    @Column(name = "title", nullable = false, length = 150)
    private String title;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20, columnDefinition = "VARCHAR(20)")
    private SituationStatus status;

    @Column(name = "ended_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime endedAt;

    /** 상황만 생성합니다. OWNER 참여 행의 동시 저장은 후속 Service의 트랜잭션 책임입니다. */
    public static Situation create(Long creatorId, String situationType, String title,
                                   LocalDate startDate, LocalDate endDate) {
        if (creatorId == null || startDate == null || endDate == null) {
            throw new IllegalArgumentException("생성자 ID와 상황 시작일·종료일은 필수입니다.");
        }
        validateText(situationType, 30, "상황 유형");
        validateText(title, 150, "상황 이름");
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("상황 시작일은 종료일보다 늦을 수 없습니다.");
        }
        Situation situation = new Situation();
        situation.creatorId = creatorId;
        situation.situationType = situationType;
        situation.title = title;
        situation.startDate = startDate;
        situation.endDate = endDate;
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

    private static void validateText(String value, int maxLength, String name) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > maxLength) {
            throw new IllegalArgumentException(name + "은 비어 있지 않은 " + maxLength + "자 이내 값이어야 합니다.");
        }
    }
}
