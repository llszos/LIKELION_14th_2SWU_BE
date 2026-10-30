package com.gyeot.domain.carerequest.entity;

import com.gyeot.domain.carerequest.enums.*;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.*;

/**
 * request_change_events - 요청 변경 이벤트 엔티티입니다.
 * 요청 변경·응답 변경·대상자 변경 등 주요 이력 관리를 담당합니다.
 * - 현재 상태는 기본 테이블(`care_requests` 등)에서 조회하고, 과거 변경 내역은 이벤트 테이블에서 조회합니다.
 * - 시스템 자동 처리 이벤트는 수행 사용자를 NULL로 둡니다.
 *
 * 이벤트 생성 규칙
 * - 모든 생성 메서드는 엔티티 상태를 바꾼 뒤에 호출합니다.
 *   새 값은 엔티티에서 직접 읽고, 사라지는 이전 값만 인자로 받습니다. (서비스는 바꾸기 전에 이전 값을 꺼내 둡니다)
 * - 요청 상태가 바뀌면 다른 이벤트와 별개로 항상 REQUEST_STATUS_CHANGED를 남깁니다.
 * - 대상 변경으로 인한 지정 마감은 TARGET_CHANGED만 남기고 ASSIGNMENT_CLOSED는 남기지 않습니다.
 * - 요청 종료로 인한 지정 마감(REQUEST_ENDED)은 REQUEST_STATUS_CHANGED가 대신 기록합니다.
 * - 장소 원문 등 민감정보는 event_details에 넣지 않습니다. 일시는 ISO-8601 문자열로 저장합니다.
 */

@Entity
@Immutable
@Table(name = "request_change_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RequestChangeEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 요청 ID (관련 요청)
     * 같은 C 영역 → 연관관계로 참조
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id", nullable = false, updatable = false)
    private CareRequest careRequest;

    // 대상 지정 ID (관련 대상 지정, 없는 이벤트는 NULL)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignment_id", updatable = false)
    private RequestAssignment assignment;

    /**
     * 수행 사용자 ID (시스템 이벤트는 NULL)
     * 다른 영역(users) → ID로만 참조
     */
    @Column(name = "actor_user_id", updatable = false)
    private Long actorUserId;

    // 이벤트 유형
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40, updatable = false)
    private ChangeEventType eventType;

    // 변경 제안 ID (재확인 관련 이벤트만, 나머지는 NULL)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "proposal_id", updatable = false)
    private RequestChangeProposal proposal;

    // 이벤트 상세 (키는 snake_case, 민감정보 원문 제외, 남길 내용이 없으면 NULL)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_details", columnDefinition = "json", updatable = false)
    private Map<String, Object> eventDetails;

    // 발생 일시
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    private RequestChangeEvent(CareRequest careRequest, RequestAssignment assignment,
                               RequestChangeProposal proposal, Long actorUserId,
                               ChangeEventType eventType, Map<String, Object> eventDetails,
                               LocalDateTime occurredAt) {
        if (eventType == null || occurredAt == null) {
            throw new IllegalArgumentException("이벤트 유형과 발생 일시는 필수입니다.");
        }
        this.careRequest = careRequest;
        this.assignment = assignment;
        this.proposal = proposal;
        this.actorUserId = actorUserId;
        this.eventType = eventType;
        this.eventDetails = (eventDetails == null || eventDetails.isEmpty())
                ? null : new LinkedHashMap<>(eventDetails);
        this.occurredAt = occurredAt;
    }

    // === 응답 ===

    /**
     * 응답 변경 (RESPONSE_CHANGED) - ERD 확정 설계
     * assignment.respond() 호출 뒤에 호출합니다. 새 응답은 지정에서 직접 읽습니다.
     * 최초 응답도 이 이벤트로 기록하며, 이때 previousStatus는 null입니다.
     * event_details 예시: {"previous_status": null, "new_status": "CHECK_LATER"}
     */
    public static RequestChangeEvent responseChanged(RequestAssignment assignment,
                                                     AssignmentResponseStatus previousStatus,
                                                     Long actorUserId, LocalDateTime occurredAt) {
        requireNotNull(assignment, "대상 지정");
        AssignmentResponseStatus newStatus = assignment.getResponseStatus();
        if (newStatus == null) {
            throw new IllegalStateException("응답하지 않은 지정입니다.");
        }

        // Map.of()는 null 값을 넣으면 NullPointerException이 나므로 LinkedHashMap 사용
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("previous_status", previousStatus == null ? null : previousStatus.name());
        details.put("new_status", newStatus.name());

        return new RequestChangeEvent(assignment.getCareRequest(), assignment, null,
                actorUserId, ChangeEventType.RESPONSE_CHANGED, details, occurredAt);
    }

    // === 대상 지정 ===

    /**
     * 대상 변경 (TARGET_CHANGED) - 다시 부탁하기
     * 이전 지정을 마감하고 새 지정을 만든 뒤에 호출합니다.
     * 이전 지정이 다른 사유(수락 철회, 급한 요청 30분 경과 등)로 이미 마감된 경우에도 이 이벤트를 남깁니다.
     * event_details 예시: {"previous_assignment_id": 3}
     */
    public static RequestChangeEvent targetChanged(RequestAssignment newAssignment,
                                                   RequestAssignment previousAssignment,
                                                   Long actorUserId, LocalDateTime occurredAt) {
        requireNotNull(newAssignment, "새 대상 지정");
        requireNotNull(previousAssignment, "이전 대상 지정");
        if (!Objects.equals(newAssignment.getCareRequest().getId(),
                previousAssignment.getCareRequest().getId())) {
            throw new IllegalArgumentException("이전 지정과 새 지정은 같은 요청에 속해야 합니다.");
        }
        if (previousAssignment.isOpen()) {
            throw new IllegalStateException("이전 지정을 먼저 마감해야 합니다.");
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("previous_assignment_id", previousAssignment.getId());

        return new RequestChangeEvent(newAssignment.getCareRequest(), newAssignment, null,
                actorUserId, ChangeEventType.TARGET_CHANGED, details, occurredAt);
    }

    /**
     * 지정 마감 (ASSIGNMENT_CLOSED)
     * assignment.close() 호출 뒤에 호출합니다. 마감 사유는 지정에서 직접 읽습니다.
     * 대상 변경(TARGET_CHANGED 사용)과 요청 종료(REQUEST_STATUS_CHANGED가 대신 기록)는 제외합니다.
     * 급한 요청 30분 경과처럼 시스템이 마감하면 actorUserId는 null입니다.
     * event_details 예시: {"close_reason": "ACCEPTANCE_WITHDRAWN"}
     */
    public static RequestChangeEvent assignmentClosed(RequestAssignment assignment,
                                                      Long actorUserId, LocalDateTime occurredAt) {
        requireNotNull(assignment, "대상 지정");
        AssignmentCloseReason reason = assignment.getCloseReason();
        if (reason == null) {
            throw new IllegalStateException("마감되지 않은 지정입니다.");
        }
        if (reason == AssignmentCloseReason.TARGET_CHANGED
                || reason == AssignmentCloseReason.REQUEST_ENDED) {
            throw new IllegalArgumentException("이 마감 사유는 다른 이벤트로 기록합니다: " + reason);
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("close_reason", reason.name());

        return new RequestChangeEvent(assignment.getCareRequest(), assignment, null,
                actorUserId, ChangeEventType.ASSIGNMENT_CLOSED, details, occurredAt);
    }

    // === 요청 ===

    /**
     * 수락 전 직접 수정 (REQUEST_UPDATED)
     * careRequest.applyChanges() 호출 뒤에 호출합니다.
     * 수정이 반영되면 원래 일정이 덮어써지므로 일정 이전 값을 남깁니다. 장소는 원문을 남기지 않고 필드 이름만 남깁니다.
     * event_details 예시:
     * {"changed_fields": ["starts_at", "place"], "previous_starts_at": "2026-10-02T14:00"}
     */
    public static RequestChangeEvent requestUpdated(CareRequest careRequest,
                                                    Set<ChangeableField> changedFields,
                                                    LocalDateTime previousStartsAt,
                                                    LocalDateTime previousEndsAt,
                                                    Long actorUserId, LocalDateTime occurredAt) {
        requireNotNull(careRequest, "요청");
        if (changedFields == null || changedFields.isEmpty()) {
            throw new IllegalArgumentException("변경한 필드가 하나 이상 있어야 합니다.");
        }
        if (careRequest.getStatus() != CareRequestStatus.REQUESTED) {
            throw new IllegalStateException(
                    "수락 전 직접 수정만 REQUEST_UPDATED로 기록합니다. 수락 후에는 재확인 이벤트를 사용합니다.");
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("changed_fields", toFieldNames(changedFields));
        putPreviousSchedule(details, changedFields, previousStartsAt, previousEndsAt);

        return new RequestChangeEvent(careRequest, null, null,
                actorUserId, ChangeEventType.REQUEST_UPDATED, details, occurredAt);
    }

    /**
     * 요청 상태 변경 (REQUEST_STATUS_CHANGED)
     * 요청 상태를 바꾼 뒤에 호출합니다. 새 상태는 요청에서 직접 읽습니다.
     * 스케줄러의 자동 시작·완료·만료는 actorUserId가 null입니다.
     * event_details 예시: {"previous_status": "ACCEPTED", "new_status": "IN_PROGRESS"}
     */
    public static RequestChangeEvent statusChanged(CareRequest careRequest,
                                                   CareRequestStatus previousStatus,
                                                   Long actorUserId, LocalDateTime occurredAt) {
        requireNotNull(careRequest, "요청");
        requireNotNull(previousStatus, "이전 상태");
        if (previousStatus == careRequest.getStatus()) {
            throw new IllegalArgumentException("상태가 바뀌지 않았습니다: " + previousStatus);
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("previous_status", previousStatus.name());
        details.put("new_status", careRequest.getStatus().name());

        return new RequestChangeEvent(careRequest, null, null,
                actorUserId, ChangeEventType.REQUEST_STATUS_CHANGED, details, occurredAt);
    }

    // === 재확인 ===
    // 요청과 대상 지정은 제안에서 꺼내 씁니다.
    // 제안 내용(proposed_*, changed_fields)은 제안 행에 있으므로 이벤트에 복사하지 않습니다.

    /**
     * 재확인 요청 (RECONFIRMATION_REQUESTED)
     * RequestChangeProposal.create()로 만든 뒤에 호출합니다.
     */
    public static RequestChangeEvent reconfirmationRequested(RequestChangeProposal proposal,
                                                             Long actorUserId, LocalDateTime occurredAt) {
        requireProposalStatus(proposal, ProposalStatus.PENDING);
        return ofProposal(ChangeEventType.RECONFIRMATION_REQUESTED, proposal,
                actorUserId, null, occurredAt);
    }

    /**
     * 재확인 수락 (RECONFIRMATION_ACCEPTED)
     * proposal.accept()와 careRequest.applyChanges() 호출 뒤에 호출합니다.
     * 반영되면 원래 일정이 덮어써지므로 일정 이전 값을 남깁니다. 일정이 바뀌지 않았으면 상세는 NULL입니다.
     * event_details 예시: {"previous_starts_at": "2026-10-02T14:00", "previous_ends_at": "2026-10-02T17:00"}
     */
    public static RequestChangeEvent reconfirmationAccepted(RequestChangeProposal proposal,
                                                            LocalDateTime previousStartsAt,
                                                            LocalDateTime previousEndsAt,
                                                            Long actorUserId, LocalDateTime occurredAt) {
        requireProposalStatus(proposal, ProposalStatus.ACCEPTED);

        Map<String, Object> details = new LinkedHashMap<>();
        putPreviousSchedule(details, proposal.getChangedFields(), previousStartsAt, previousEndsAt);

        return ofProposal(ChangeEventType.RECONFIRMATION_ACCEPTED, proposal,
                actorUserId, details, occurredAt);
    }

    /**
     * 재확인 거절 (RECONFIRMATION_REJECTED)
     * proposal.reject() 호출 뒤에 호출합니다.
     * 이어지는 요청 취소는 statusChanged()로 따로 기록합니다.
     */
    public static RequestChangeEvent reconfirmationRejected(RequestChangeProposal proposal,
                                                            Long actorUserId, LocalDateTime occurredAt) {
        requireProposalStatus(proposal, ProposalStatus.REJECTED);
        return ofProposal(ChangeEventType.RECONFIRMATION_REJECTED, proposal,
                actorUserId, null, occurredAt);
    }

    /**
     * 재확인 무효화 (RECONFIRMATION_INVALIDATED)
     * proposal.invalidate() 호출 뒤에 호출합니다.
     * 제안 테이블에 무효화 사유 컬럼이 없으므로 이벤트에 남깁니다. 기한 만료·요청 시작은 actorUserId가 null입니다.
     * event_details 예시: {"reason": "DEADLINE_PASSED"}
     */
    public static RequestChangeEvent reconfirmationInvalidated(RequestChangeProposal proposal,
                                                               InvalidationReason reason,
                                                               Long actorUserId, LocalDateTime occurredAt) {
        requireProposalStatus(proposal, ProposalStatus.INVALIDATED);
        requireNotNull(reason, "무효화 사유");

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason.name());

        return ofProposal(ChangeEventType.RECONFIRMATION_INVALIDATED, proposal,
                actorUserId, details, occurredAt);
    }

    // === 조회용 메서드 ===

    /** 스케줄러 등 시스템이 자동 처리한 이벤트인지 */
    public boolean isSystemEvent() {
        return actorUserId == null;
    }

    // === 내부 처리 ===
    private static RequestChangeEvent ofProposal(ChangeEventType eventType, RequestChangeProposal proposal,
                                                 Long actorUserId, Map<String, Object> details,
                                                 LocalDateTime occurredAt) {
        return new RequestChangeEvent(proposal.getCareRequest(), proposal.getAssignment(), proposal,
                actorUserId, eventType, details, occurredAt);
    }

    /** DB에는 ["starts_at", "place"]처럼 컬럼 이름으로 저장합니다. */
    private static List<String> toFieldNames(Set<ChangeableField> fields) {
        return fields.stream().map(ChangeableField::getFieldName).toList();
    }

    /**
     * 일정이 바뀐 경우에만 이전 일정을 남깁니다.
     * LocalDateTime을 그대로 넣으면 JSON 변환 설정에 따라 배열로 저장되거나 오류가 날 수 있어 문자열로 바꿉니다.
     */
    private static void putPreviousSchedule(Map<String, Object> details, Set<ChangeableField> changedFields,
                                            LocalDateTime previousStartsAt, LocalDateTime previousEndsAt) {
        if (changedFields.contains(ChangeableField.STARTS_AT)) {
            requireNotNull(previousStartsAt, "이전 시작 일시");
            details.put("previous_starts_at", previousStartsAt.toString());
        }
        if (changedFields.contains(ChangeableField.ENDS_AT)) {
            requireNotNull(previousEndsAt, "이전 종료 일시");
            details.put("previous_ends_at", previousEndsAt.toString());
        }
    }

    // === 내부 검증 ===
    // TODO: B 공통 에러 코드가 병합되면 팀 공통 예외로 교체

    private static void requireNotNull(Object value, String name) {
        if (Objects.isNull(value)) {
            throw new IllegalArgumentException(name + "은(는) 필수입니다.");
        }
    }

    private static void requireProposalStatus(RequestChangeProposal proposal, ProposalStatus expected) {
        requireNotNull(proposal, "변경 제안");
        if (proposal.getStatus() != expected) {
            throw new IllegalStateException(
                    "제안 상태가 맞지 않습니다. 현재: " + proposal.getStatus() + ", 필요: " + expected);
        }
    }
}
