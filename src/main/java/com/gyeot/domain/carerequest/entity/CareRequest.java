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
import com.gyeot.domain.carerequest.enums.ChangeableField;
import java.util.Set;

/**
 * care_requests - 돌봄 요청 엔티티입니다.
 * 돌봄 요청의 내용·일정·진행 상태 관리를 담당합니다.
 */
@Entity
@Table(name = "care_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CareRequest extends BaseEntity {

    // 요청 ID
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 상황 ID
     * 다른 영역(situations, users)은 ID로만 참조
     */
    @Column(name = "situation_id")
    private Long situationId; // 일반 요청 필수, 급한 요청 NULL

    /**
     * 요청자 ID
     * 다른 영역(situations, users)은 ID로만 참조
     */
    @Column(name = "requester_id", nullable = false, updatable = false)
    private Long requesterId;

    // 요청 유형
    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 20, updatable = false)
    private RequestType requestType;

    // 도움 유형
    @Enumerated(EnumType.STRING)
    @Column(name = "help_type", nullable = false, length = 30, updatable = false)
    private HelpType helpType;

    // 요청 내용
    @Column(name = "description", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String description;

    // 요청 장소
    @Column(name = "place", columnDefinition = "TEXT")
    private String place;

    // 예정 시작 일시
    @Column(name = "starts_at")
    private LocalDateTime startsAt; // 일반 요청 필수, 급한 요청 NULL

    // 예정 종료 일시
    @Column(name = "ends_at")
    private LocalDateTime endsAt; // 일반 요청 필수, 급한 요청 NULL

    // 요청 상태
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CareRequestStatus status;

    /** TODO: SHOULD 기능이므로 1차 추가 때 재확인이 필요합니다.
     *  체크리스트 저장 구조는 JSON 칼럼을 사용할 예정이고, 지금은 필드만 먼저 넣어두었습니다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "checklist", columnDefinition = "json", updatable = false)
    private List<ChecklistItem> checklist; // F-12 (SHOULD), NULL 허용

    // 실제 시작 처리 일시
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    // 완료 일시
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    // 취소 일시
    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    // 만료 일시
    @Column(name = "expired_at")
    private LocalDateTime expiredAt;

    // 취소 사유
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
     * (requester_id = situations.creator_id, 요청 시간이 상황 기간 안에 포함, 시작 1시간 전ㅡ 대상자가 나의 사람 및 보호자이면서 참여자,
     * 도움 유형별 장소 필수 등은 서비스 위치에서 검증
     */
    public static CareRequest createGeneral(Long situationId, Long requesterId, HelpType helpType,
                                            String description, String place,
                                            LocalDateTime startsAt, LocalDateTime endsAt,
                                            List<ChecklistItem> checklist) {
        // 일반 요청은 상황과 일정이 필수입니다.
        if (situationId == null || startsAt == null || endsAt == null) {
            throw new IllegalArgumentException("일반 요청은 상황과 일정이 필수입니다.");
        }

        // starts_at < ends_at을 검증합니다.
        if (!startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException("시작 시간은 종료 시간보다 빨라야 합니다.");
        }
        return new CareRequest(situationId, requesterId, RequestType.GENERAL, helpType,
                description, place, startsAt, endsAt, checklist);
    }

    // === 급한 요청 생성 메서드 ===

    /**
     * 급한 요청 생성
     * 상황과 일정 없이 생성하므로 situation_id, starts_at, ends_at은 NULL
     */
    public static CareRequest createUrgent(Long requesterId, HelpType helpType,
                                           String description, String place,
                                           List<ChecklistItem> checklist) {
        return new CareRequest(null, requesterId, RequestType.URGENT, helpType,
                description, place, null, null, checklist);
    }

    //  === 상태 변경 메서드 ===
    /**
     * TODO: 급한 요청 기준(F-13)은 SHOULD로 MUST 개발 이후에 재확인이 필요합니다.
     */

    /**
     * 현재 대상자 수락: REQUESTED → ACCEPTED
     */
    public void accept() {
        requireStatus(REQUESTED);
        this.status = ACCEPTED;
    }

    /**
     * 담당자가 빠져 다시 요청됨으로 복귀: ACCEPTED -> REQUESTED
     * 일반: 시작 전 수락 철회·상황 이탈·관계 해제
     * 급한: 담당자 이탈·관계 해제
     */
    public void revertToRequested() {
        requireStatus(ACCEPTED);
        this.status = REQUESTED;
    }

    /**
     * 요청 시작 (일반 요청만): ACCEPTED → IN_PROGRESS
     * 담당자가 시작 버튼 클릭 또는 시작 시각 도달했을 시
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
     * 요청자 취소, 상황 종료, 재확인 거절, 진행 중 담당자 이탈, 관계 해제, 회원 탈퇴
     */
    public void cancel(CancelReason reason, LocalDateTime now) {
        if (reason == null) {
            throw new IllegalArgumentException("취소 사유는 필수입니다.");
        }
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

    /**
     * 요청 수정 반영 (수락 전 직접 수정, 수락 후 재확인 수락 시 공통)
     * changed_fields에 있는 필드만 적용하고, 공개 항목(information_ids)은 D가 권한으로 처리
     */
    public void applyChanges(Set<ChangeableField> fields, LocalDateTime newStartsAt,
                             LocalDateTime newEndsAt, String newPlace) {

        if (status != REQUESTED && status != ACCEPTED) {
            throw new IllegalStateException("진행 중이거나 종료된 요청은 수정할 수 없습니다: " + status);
        }
        boolean scheduleChanged = fields.contains(ChangeableField.STARTS_AT)
                || fields.contains(ChangeableField.ENDS_AT);

        if (scheduleChanged) {
            requireGeneral();
            LocalDateTime start = fields.contains(ChangeableField.STARTS_AT) ? newStartsAt : this.startsAt;
            LocalDateTime end = fields.contains(ChangeableField.ENDS_AT) ? newEndsAt : this.endsAt;

            if (start == null || end == null || !start.isBefore(end)) {
                throw new IllegalArgumentException("일정은 필수이며 시작 시각은 종료 시각보다 빨라야 합니다.");
            }
            this.startsAt = start;
            this.endsAt = end;
        }
        if (fields.contains(ChangeableField.PLACE)) {
            if (!isUrgent() && (newPlace == null || newPlace.isBlank())) {
                throw new IllegalArgumentException("일반 요청은 장소를 비울 수 없습니다.");
            }
            this.place = newPlace;
        }
    }

    // === 조회용 메소드 ===

    /**
     * 급한 요청인지 확인
     */
    public boolean isUrgent() {
        return requestType == RequestType.URGENT;
    }

    /**
     * 상태가 완료 또는 만료 또는 취소인지 확인
     */
    public boolean isTerminal() {
        return status.isTerminal();
    }

    // === 내부 검증 ===
    // TODO: B 영역 공통 에러 코드가 병합되면 팀 공통 예외로 교체
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