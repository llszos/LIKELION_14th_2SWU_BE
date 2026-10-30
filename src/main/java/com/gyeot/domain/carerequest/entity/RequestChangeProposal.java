package com.gyeot.domain.carerequest.entity;

import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.ChangeableField;
import com.gyeot.domain.carerequest.enums.ProposalStatus;
import com.gyeot.global.common.entity.BaseCreatedEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.*;

/**
 * request_change_proposals - 요청 변경 제안 엔티티입니다.
 * 수락 후 주요 변경(일정, 장소, 공개 항목)에 대한 담당자 재확인 관리를 담당합니다.
 * - 변경 제안은 ACCEPTED 요청에서만 생성합니다.
 * - 요청당 PENDING 제안은 최대 1건입니다. (DDL의 생성 컬럼 + UNIQUE로 보장, 엔티티에는 매핑하지 않음)
 * - 제안 내용(proposed_*, changed_fields)은 한 번 만들면 변경하지 않습니다.
 * - 대기 중에는 원본 요청의 내용과 수락 상태를 그대로 유지합니다.
 */

@Entity
@Table(name = "request_change_proposals",
        indexes = {
                @Index(name = "idx_proposals_assignment_id_status_response_deadline",
                        columnList = "assignment_id, status, response_deadline") // 재확인 대기
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RequestChangeProposal extends BaseCreatedEntity {

    // 변경 제안 ID
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 요청 ID (변경 대상 요청)
     * 같은 C 영역 → 연관관계로 참조
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id", nullable = false, updatable = false)
    private CareRequest careRequest;

    /**
     * 대상 지정 ID (재확인 대상 지정)
     * 같은 C 영역 → 연관관계로 참조
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignment_id", nullable = false, updatable = false)
    private RequestAssignment assignment;

    /**
     * 변경 예정 공개 항목 (변경 후 공유할 안전정보 ID 전체 목록)
     * changed_fields에 information_ids가 있을 때만 값이 있음, 모두 제외하면 빈 배열
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposed_information_ids", columnDefinition = "json", updatable = false)
    private List<Long> proposedInformationIds;

    /** 변경 예정 장소 (제안된 장소)
     * changed_fields에 place가 있고 급한 요청만 NULL로 지울 수 있음
     * */
    @Column(name = "proposed_place", columnDefinition = "TEXT", updatable = false)
    private String proposedPlace;

    // 변경 예정 시작 일시 (제안된 시작 시각)
    @Column(name = "proposed_starts_at", updatable = false)
    private LocalDateTime proposedStartsAt;

    // 변경 예정 종료 일시 (제안된 종료 시각)
    @Column(name = "proposed_ends_at", updatable = false)
    private LocalDateTime proposedEndsAt;

    /**
     * 변경 필드 목록 (실제 변경한 필드명)
     * DB에는 ["starts_at", "place"] 형태로 저장 (ChangeableField의 @JsonValue)
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changed_fields", columnDefinition = "json", nullable = false, updatable = false)
    private Set<ChangeableField> changedFields;

    // 변경 제안 상태 (대기, 수락, 거절, 무효)
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProposalStatus status;

    /**
     * 재확인 기한
     * 기본 제안 +24시간, 기존·변경 예정 시작 중 빠른 시각 이전, create()에서 계산합니다
     */
    @Column(name = "response_deadline", nullable = false, updatable = false)
    private LocalDateTime responseDeadline;

    // 재확인 응답 일시 (수락, 거절 시 기록)
    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    private RequestChangeProposal(CareRequest careRequest, RequestAssignment assignment,
                                  Set<ChangeableField> changedFields,
                                  LocalDateTime proposedStartsAt, LocalDateTime proposedEndsAt,
                                  String proposedPlace, List<Long> proposedInformationIds,
                                  LocalDateTime responseDeadline) {
        this.careRequest = careRequest;
        this.assignment = assignment;
        this.changedFields = new LinkedHashSet<>(changedFields);
        this.proposedStartsAt = proposedStartsAt;
        this.proposedEndsAt = proposedEndsAt;
        this.proposedPlace = proposedPlace;
        this.proposedInformationIds =
                proposedInformationIds == null ? null : new ArrayList<>(proposedInformationIds);
        this.responseDeadline = responseDeadline;
        this.status = ProposalStatus.PENDING;
    }

    // === 생성 메서드 ===
    /**
     * 변경 제안 생성 (수락 후 주요 변경 시)
     * 요청자 본인 확인, 요청당 PENDING 1건 확인은 서비스에서 처리합니다
     */
    public static RequestChangeProposal create(CareRequest careRequest, RequestAssignment assignment,
                                               Set<ChangeableField> changedFields,
                                               LocalDateTime proposedStartsAt, LocalDateTime proposedEndsAt,
                                               String proposedPlace, List<Long> proposedInformationIds,
                                               LocalDateTime now) {
        validateTarget(careRequest, assignment);
        validateChangedFields(careRequest, changedFields, proposedStartsAt, proposedEndsAt,
                proposedPlace, proposedInformationIds);
        validateSchedule(careRequest, changedFields, proposedStartsAt, proposedEndsAt);

        LocalDateTime deadline = calculateDeadline(careRequest, changedFields, proposedStartsAt, now);

        return new RequestChangeProposal(careRequest, assignment, changedFields,
                proposedStartsAt, proposedEndsAt, proposedPlace, proposedInformationIds, deadline);
    }

    /** 재확인 기한: 제안 +24시간, 기존·변경 예정 시작 중 빠른 시각을 넘지 않음 */
    private static LocalDateTime calculateDeadline(CareRequest careRequest,
                                                   Set<ChangeableField> changedFields,
                                                   LocalDateTime proposedStartsAt,
                                                   LocalDateTime now) {
        LocalDateTime deadline = now.plusHours(24);

        LocalDateTime currentStart = careRequest.getStartsAt();
        if (currentStart != null && currentStart.isBefore(deadline)) {
            deadline = currentStart;
        }
        if (changedFields.contains(ChangeableField.STARTS_AT) && proposedStartsAt.isBefore(deadline)) {
            deadline = proposedStartsAt;
        }

        // 변경 예정 시작이 이미 지났거나 지금이면 재확인할 시간이 없음
        if (!deadline.isAfter(now)) {
            throw new IllegalArgumentException("재확인할 시간이 남아 있지 않습니다.");
        }
        return deadline;
    }


    // === 상태 변경 메서드 ===
    /**
     * 재확인 수락: PENDING → ACCEPTED
     * 원본 요청 반영(careRequest.applyChanges())과 공개 항목 권한 처리(D)는 서비스에서 호출합니다.
     */
    public void accept(LocalDateTime now) {
        requirePending();
        requireStillValid(now);
        this.status = ProposalStatus.ACCEPTED;
        this.respondedAt = now;
    }

    /**
     * 재확인 거절: PENDING → REJECTED
     * 요청 취소(RECONFIRMATION_REJECTED)와 지정 마감은 서비스에서 호출합니다.
     */
    public void reject(LocalDateTime now) {
        requirePending();
        requireStillValid(now);
        this.status = ProposalStatus.REJECTED;
        this.respondedAt = now;
    }

    /**
     * 제안 무효화: PENDING → INVALIDATED
     * 기한 만료, 요청 시작, 담당자 지정 종료, 요청 종료 시 무효화됩니다.
     * 기존 요청과 공개 항목, 권한은 그대로 유지됩니다.
     * 무효화 시각을 저장하는 컬럼은 없으므로 responded_at은 기록하지 않습니다.
     */
    public void invalidate() {
        requirePending();
        this.status = ProposalStatus.INVALIDATED;
    }

    // === 조회용 메소드 ===
    public boolean isPending() {
        return status == ProposalStatus.PENDING;
    }

    public boolean isDeadlinePassed(LocalDateTime now) {
        return now.isAfter(responseDeadline);
    }

    // 해당 필드를 변경하는 제안인지
    public boolean changes(ChangeableField field) {
        return changedFields.contains(field);
    }

    // === 생성 시 검증 ===
    // TODO: B 공통 에러 코드가 병합되면 팀 공통 예외로 교체

    // 수락된 요청의 현재 담당자에게만 제안할 수 있습니다.
    private static void validateTarget(CareRequest careRequest, RequestAssignment assignment) {
        if (careRequest == null || assignment == null) {
            throw new IllegalArgumentException("요청과 대상 지정은 필수입니다.");
        }
        if (careRequest.getStatus() != CareRequestStatus.ACCEPTED) {
            throw new IllegalStateException("변경 제안은 수락된 요청에서만 만들 수 있습니다: "
                    + careRequest.getStatus());
        }
        if (!Objects.equals(assignment.getCareRequest().getId(), careRequest.getId())) {
            throw new IllegalArgumentException("제안과 대상 지정은 같은 요청에 속해야 합니다.");
        }
        if (!assignment.isCurrentAssignee()) {
            throw new IllegalStateException("현재 담당자에게만 재확인을 요청할 수 있습니다.");
        }
    }

    /**
     * changed_fields와 제안값의 일관성 검증
     * - 포함된 필드: 해당 제안값 적용 (place는 NULL이면 장소를 지우는 변경)
     * - 포함되지 않은 필드: 제안값은 NULL
     * - starts_at, ends_at: 포함됐다면 NULL 불가 (필수 컬럼)
     * - information_ids: 포함됐다면 NULL 불가 (모두 제외는 빈 배열)
     */
    private static void validateChangedFields(CareRequest careRequest,
                                              Set<ChangeableField> changedFields,
                                              LocalDateTime proposedStartsAt,
                                              LocalDateTime proposedEndsAt,
                                              String proposedPlace,
                                              List<Long> proposedInformationIds) {
        if (changedFields == null || changedFields.isEmpty()) {
            throw new IllegalArgumentException("변경한 필드가 하나 이상 있어야 합니다.");
        }

        boolean changesStart = changedFields.contains(ChangeableField.STARTS_AT);
        boolean changesEnd = changedFields.contains(ChangeableField.ENDS_AT);
        boolean changesPlace = changedFields.contains(ChangeableField.PLACE);
        boolean changesInfo = changedFields.contains(ChangeableField.INFORMATION_IDS);

        if ((changesStart || changesEnd) && careRequest.isUrgent()) {
            throw new IllegalStateException("급한 요청은 일정을 변경할 수 없습니다.");
        }

        if (changesStart != (proposedStartsAt != null)) {
            throw new IllegalArgumentException(
                    "시작 일시는 변경할 때만 값이 있어야 하며, NULL로 변경할 수 없습니다.");
        }
        if (changesEnd != (proposedEndsAt != null)) {
            throw new IllegalArgumentException(
                    "종료 일시는 변경할 때만 값이 있어야 하며, NULL로 변경할 수 없습니다.");
        }
        if (!changesPlace && proposedPlace != null) {
            throw new IllegalArgumentException("장소를 변경하지 않는다면 변경 예정 장소는 비어 있어야 합니다.");
        }

        // 일반 요청은 장소가 필수라 비우는 변경을 할 수 없습니다.
        if (changesPlace && !careRequest.isUrgent()
                && (proposedPlace == null || proposedPlace.isBlank())) {
            throw new IllegalArgumentException("일반 요청은 장소를 비울 수 없습니다.");
        }

        if (changesInfo != (proposedInformationIds != null)) {
            throw new IllegalArgumentException(
                    "공개 항목은 변경할 때만 값이 있어야 하며, 모두 제외할 때는 빈 목록을 사용합니다.");
        }
    }

    /** 변경 후 일정(제안값 또는 기존값)에서도 시작 시간 < 종료 시간 */
    private static void validateSchedule(CareRequest careRequest,
                                         Set<ChangeableField> changedFields,
                                         LocalDateTime proposedStartsAt,
                                         LocalDateTime proposedEndsAt) {
        LocalDateTime start = changedFields.contains(ChangeableField.STARTS_AT)
                ? proposedStartsAt : careRequest.getStartsAt();
        LocalDateTime end = changedFields.contains(ChangeableField.ENDS_AT)
                ? proposedEndsAt : careRequest.getEndsAt();

        if (start != null && end != null && !start.isBefore(end)) {
            throw new IllegalArgumentException("변경 후 시작 시각은 종료 시각보다 빨라야 합니다.");
        }
    }

    // === 상태 변경 시 검증 ===

    private void requirePending() {
        if (!isPending()) {
            throw new IllegalStateException("대기 중인 변경 제안만 처리할 수 있습니다: " + status);
        }
    }

    /**
     * 스케줄러가 처리하기 직전에 응답이 들어오는 경우를 대비한 재확인 메서드입니다.
     * - 기한이 지났거나
     * - 담당자 지정이 종료됐거나
     * - 요청이 이미 시작·종료되어 ACCEPTED가 아니면 처리할 수 없습니다.
     */
    private void requireStillValid(LocalDateTime now) {
        if (isDeadlinePassed(now)) {
            throw new IllegalStateException("재확인 기한이 지난 제안입니다.");
        }
        if (!assignment.isCurrentAssignee()) {
            throw new IllegalStateException("담당자 지정이 종료되어 처리할 수 없는 제안입니다.");
        }
        if (careRequest.getStatus() != CareRequestStatus.ACCEPTED) {
            throw new IllegalStateException("수락 상태가 아닌 요청의 제안은 처리할 수 없습니다: "
                    + careRequest.getStatus());
        }
    }
}
