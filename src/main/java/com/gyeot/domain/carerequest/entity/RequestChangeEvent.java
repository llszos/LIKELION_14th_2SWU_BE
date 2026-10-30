package com.gyeot.domain.carerequest.entity;

import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import com.gyeot.domain.carerequest.enums.ChangeEventType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * request_change_events - 요청 변경 이벤트 엔티티입니다.
 * 요청 변경·응답 변경·대상자 변경 등 주요 이력 관리를 담당합니다.
 * - 요청 내용 수정, 대상자 변경, 응답 변경, 재확인 수락·거절·만료 등 주요 이벤트를 기록합니다.
 * - 현재 상태는 기본 테이블(`care_requests` 등)에서 조회하고, 과거 변경 내역은 이벤트 테이블에서 조회합니다.
 * - 요청 내용·주소·안심카드 원문 등 민감정보를 이벤트 상세에 불필요하게 복제하지 않습니다.
 * - 시스템 자동 처리 이벤트는 수행 사용자를 NULL로 둘 수 있습니다.
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

    // 이벤트 상세 (변경 전/후 메타데이터, 키는 snake_case, 민감정보 원문 제외)
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
        this.eventDetails = eventDetails == null ? null : new LinkedHashMap<>(eventDetails);
        this.occurredAt = occurredAt;
    }

    // === 생성 메서드 ===
    // 이벤트 종류마다 필요한 연관관계가 다르므로 생성 메서드를 나뉩니다.
    // 대상 지정이나 제안을 받으면 careRequest는 거기서 꺼내 써서, 다른 요청과 섞이지 않게 합니다.

    /**
     * 응답 변경 (RESPONSE_CHANGED) - ERD 확정 설계
     * 최초 응답도 이 이벤트로 기록하며, 이때 previousStatus는 null입니다.
     * 서비스에서는 assignment.respond()를 호출하기 전에 이전 응답을 꺼내 두어야 합니다.
     * event_details 예시: {"previous_status": null, "new_status": "CHECK_LATER"}
     */
    public static RequestChangeEvent responseChanged(RequestAssignment assignment, Long actorUserId,
                                                     AssignmentResponseStatus previousStatus,
                                                     AssignmentResponseStatus newStatus,
                                                     LocalDateTime occurredAt) {
        requireNotNull(assignment, "대상 지정");
        requireNotNull(newStatus, "새 응답");

        // Map.of()는 null 값을 넣으면 NullPointerException이 나므로 LinkedHashMap 사용
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("previous_status", previousStatus == null ? null : previousStatus.name());
        details.put("new_status", newStatus.name());

        return new RequestChangeEvent(assignment.getCareRequest(), assignment, null,
                actorUserId, ChangeEventType.RESPONSE_CHANGED, details, occurredAt);
    }

    /**
     * 대상 지정 관련 이벤트
     * - TARGET_CHANGED: 대상자 변경. assignment에는 새로 만든 지정을 넘깁니다.
     * - ASSIGNMENT_CLOSED: 대상자 변경 외의 이유로 지정이 마감된 경우. assignment에는 마감된 지정을 넘깁니다.
     *   예: {"close_reason": "ACCEPTANCE_WITHDRAWN"}
     * 대상자 변경으로 인한 마감은 TARGET_CHANGED만 기록하고 ASSIGNMENT_CLOSED는 기록하지 않습니다.
     */
    public static RequestChangeEvent ofAssignment(ChangeEventType eventType, RequestAssignment assignment,
                                                  Long actorUserId, Map<String, Object> details,
                                                  LocalDateTime occurredAt) {
        requireNotNull(assignment, "대상 지정");
        if (eventType != ChangeEventType.TARGET_CHANGED && eventType != ChangeEventType.ASSIGNMENT_CLOSED) {
            throw new IllegalArgumentException("대상 지정 이벤트가 아닙니다: " + eventType
                    + " (응답 변경은 responseChanged()를 사용)");
        }
        return new RequestChangeEvent(assignment.getCareRequest(), assignment, null,
                actorUserId, eventType, details, occurredAt);
    }

    /**
     * 재확인 관련 이벤트 (RECONFIRMATION_REQUESTED / ACCEPTED / REJECTED / INVALIDATED)
     * 요청과 대상 지정은 제안에서 꺼내 씁니다.
     * 기한 만료로 인한 INVALIDATED는 시스템 이벤트이므로 actorUserId는 null입니다.
     */
    public static RequestChangeEvent ofProposal(ChangeEventType eventType, RequestChangeProposal proposal,
                                                Long actorUserId, Map<String, Object> details,
                                                LocalDateTime occurredAt) {
        requireNotNull(proposal, "변경 제안");
        if (eventType == null || !eventType.isReconfirmation()) {
            throw new IllegalArgumentException("재확인 이벤트가 아닙니다: " + eventType);
        }
        return new RequestChangeEvent(proposal.getCareRequest(), proposal.getAssignment(), proposal,
                actorUserId, eventType, details, occurredAt);
    }

    /**
     * 요청 단위 이벤트 (REQUEST_UPDATED / REQUEST_STATUS_CHANGED)
     * - REQUEST_UPDATED: 수락 전 직접 수정. 바뀐 값이 아니라 필드 이름만 기록합니다.
     *   예: {"changed_fields": ["place", "starts_at"]}
     * - REQUEST_STATUS_CHANGED: 예: {"previous_status": "ACCEPTED", "new_status": "IN_PROGRESS"}
     *   스케줄러의 자동 시작·완료·만료는 actorUserId가 null입니다.
     */
    public static RequestChangeEvent ofRequest(ChangeEventType eventType, CareRequest careRequest,
                                               Long actorUserId, Map<String, Object> details,
                                               LocalDateTime occurredAt) {
        requireNotNull(careRequest, "요청");
        if (eventType == null || !eventType.isRequestLevel()) {
            throw new IllegalArgumentException("요청 단위 이벤트가 아닙니다: " + eventType);
        }
        return new RequestChangeEvent(careRequest, null, null,
                actorUserId, eventType, details, occurredAt);
    }

    // ===== 조회용 메서드 =====

    /** 스케줄러 등 시스템이 자동 처리한 이벤트인지 */
    public boolean isSystemEvent() {
        return actorUserId == null;
    }

    // ===== 내부 검증 =====
    // TODO: B 공통 에러 코드가 병합되면 팀 공통 예외로 교체

    private static void requireNotNull(Object value, String name) {
        if (Objects.isNull(value)) {
            throw new IllegalArgumentException(name + "은(는) 필수입니다.");
        }
    }
}
