package com.gyeot.domain.carerequest.entity;

import com.gyeot.domain.carerequest.enums.CancelReason;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.RequestType;
import com.gyeot.global.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.List;
import java.time.LocalDateTime;
import static com.gyeot.domain.carerequest.enums.CareRequestStatus.*;

@Entity
@Table(name = "care_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CareRequest extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 다른 영역(situations, users)은 ID로만 참조
    @Column(name = "situation_id")
    private Long situationId; // 일반 요청 필수, 급한 요청 NULL

    @Column(name = "requester_id", nullable = false, updatable = false)
    private Long requesterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 20, updatable = false)
    private RequestType requestType;

    @Enumerated(EnumType.STRING)
    @Column(name = "help_type", nullable = false, length = 30, updatable = false)
    private HelpType helpType;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String description;

    @Column(name = "place", columnDefinition = "TEXT")
    private String place;

    @Column(name = "starts_at")
    private LocalDateTime startsAt; // 일반 요청 필수, 급한 요청 NULL

    @Column(name = "ends_at")
    private LocalDateTime endsAt; // 알반 요청 필수, 급한 요청 NULL

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CareRequestStatus status;

    // TODO: 코드 재확인 필요
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "checklist", columnDefinition = "json")
    private List<ChecklistItem> checklist; // F-12 (SHOULD), NULL 허용

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @Column(name = "expired_at")
    private LocalDateTime expiredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason", length = 40)
    private CancelReason cancelReason;

    private CareRequest(Long situationId, Long requesterId, RequestType requestType,
                        HelpType helpType, String description, String place,
                        LocalDateTime startsAt, LocalDateTime endsAt,
                        List<ChecklistItem> checklist) {
        this.situationId = situationId;
        this.requesterId = requesterId;
        this.requestType = requestType;
        this.helpType = helpType;
        this.description = description;
        this.place = place;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.checklist = checklist;
        this.status = REQUESTED;
    }

    // === 생성 메서드 ===

    /**
     * 일반 요청 생성
     * 엔티티가 혼자 판단할 수 있는 규칙만 검증
     * (requester = 상황 생성자, 상황 기간 포함, 시작 1시간 전 등은 서비스에서 검증)
     */
    public static CareRequest createGeneral(Long situationId, Long requesterId, HelpType helpType,
                                            String description, String place,
                                            LocalDateTime startsAt, LocalDateTime endsAt,
                                            List<ChecklistItem> checklist) {
        if (situationId == null || startsAt == null || endsAt == null) {
            throw new IllegalArgumentException("일반 요청은 상황과 일정이 필수입니다.");
        }
        if (!startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException("시작 시간은 종료 시간보다 빨라야 합니다.");
        }
        return new CareRequest(situationId, requesterId, RequestType.GENERAL, helpType,
                description, place, startsAt, endsAt, checklist);
    }

    // === 급한 요청 생성 메서드 ===

    /**
     * 상황과 일정 없이 생성하므로 situation_id, starts_at, ends_at은 NULL
     */
    public static CareRequest createUrgent(Long requesterId, HelpType helpType,
                                           String description, String place,
                                           List<ChecklistItem> checklist) {
        return new CareRequest(null, requesterId, RequestType.URGENT, helpType,
                description, place, null, null, checklist);
    }

    //  === TODO: 상태 변경 메서드 (MUST 기간 F-08에서 재확인) ===

    /**
     * 현재 대상자 수락: REQUESTED → ACCEPTED
     */
    public void accept() {
        requireStatus(REQUESTED);
        this.status = ACCEPTED;
    }

    /**
     * 담당자가 빠져 다시 요청됨으로 복귀: ACCEPTED -> REQUESTED
     * 일반 요청: 시작 전 수락 철회, 상황 이탈, 관계 해제
     * 급한 요청: 담당자 관계 해제, 이탈
     */
    public void revertToRequested() {
        requireStatus(ACCEPTED);
        this.status = REQUESTED;
    }

    /**
     * 요청 시작 (일반 요청만): ACCEPTED → IN_PROGRESS
     * 시작 버튼 또는 시작 시각 도달
     */
    public void start(LocalDateTime now) {
        requireGeneral();
        requireStatus(ACCEPTED);
        this.status = IN_PROGRESS;
        this.startedAt = now;
    }

    /**
     * 요청 완료
     * 일반 요청: IN_PROGRESS -> COMPLETED (완료 버튼 또는 종료 시각 도달)
     * 급한 요청: ACCEPTED -> COMPLETED (요청자 종료)
     */
    public void complete(LocalDateTime now) {
        requireStatus(isUrgent() ? ACCEPTED : IN_PROGRESS);
        this.status = COMPLETED;
        this.completedAt = now;
    }

    /**
     * 요청 취소: 종료되지 않은 모든 상태 -> CANCELED
     * 요청자 취소, 상황 종료, 재확인 거절, 진행 중 담당자 이탈·관계 해제, 회원 탈퇴
     */
    public void cancel(CancelReason reason, LocalDateTime now) {
        if (status.isTerminal()) {
            throw new IllegalStateException("이미 종료된 요청은 취소할 수 없습니다: " + status);
        }
        this.status = CANCELED;
        this.cancelReason = reason;
        this.canceledAt = now;
    }

    /**
     * 담당자 없이 시작 시각 도달 (일반 요청만): REQUESTED -> EXPIRED
     */
    public void expire(LocalDateTime now) {
        requireGeneral();
        requireStatus(REQUESTED);
        this.status = EXPIRED;
        this.expiredAt = now;
    }

    // === 조회용 메소드 ===
    public boolean isUrgent() {
        return requestType == RequestType.URGENT;
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    // === 내부 검증 ===
    // TODO: B의 공통 에러 코드가 병합되면 팀 공통 예외로 교체
    private void requireStatus(CareRequestStatus expected) {
        if (this.status != expected) {
            throw new IllegalStateException(
                    "허용되지 않은 상태 전이입니다. 현재: " + status + ", 필요: " + expected);
        }
    }

    private void requireGeneral() {
        if (isUrgent()) {
            throw new IllegalStateException("급한 요청에는 적용할 수 없는 처리입니다.");
        }
    }
}