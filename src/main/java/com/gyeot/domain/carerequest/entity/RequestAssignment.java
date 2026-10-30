package com.gyeot.domain.carerequest.entity;

import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.NoAssigneeReason;
import com.gyeot.global.common.entity.BaseCreatedEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import java.time.LocalDateTime;

/**
 * request_assignments - 요청 대상자 지정 엔티티입니다.
 * 요청 대상자, 응답 상태, 대상 변경 이력 관리를 담당합니다.
 * 같은 요청에서 동일 사용자를 다시 지정할 수 없습니다. (uk_request_assignments_target, ERD 4.7·6.2)
 * 요청당 현재 지정 최대 1건은 생성 컬럼 + UNIQUE라 DDL에서만 관리하고 엔티티에는 매핑하지 않았습니다.
 */
@Entity
@Table(
        name = "request_assignments",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_request_assignments_target",
                columnNames = {"request_id", "target_user_id"}
        ),
        indexes = {
                @Index(name = "idx_assignments_request_id_closed_at", columnList = "request_id, closed_at"), // 현재 대상자 조회
                @Index(name = "idx_assignments_target_user_id_closed_at_response_deadline",
                        columnList = "target_user_id, closed_at, response_deadline")  // 받은 요청/응답 필요 요청
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RequestAssignment extends BaseCreatedEntity {

    // 대상 지정 ID
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 요청 ID (연결된 요청)
     * 같은 C 영역 -> ID 말고 연관관계로 참조
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id", nullable = false, updatable = false)
    private CareRequest careRequest;

    /**
     * 대상 사용자 ID (요청을 받은 사용자)
     * 다른 영역 (users) -> ID로만 참조
     */
    @Column(name = "target_user_id", nullable = false, updatable = false)
    private Long targetUserId;

    // 대상 지정 순서 (요청 내 지정 순서)
    @Column(name = "assignment_order", nullable = false, updatable = false)
    private int assignmentOrder; // 요청 내 지정 순서, 서비스에서 계산

    // 응답 상태 (가능·어려움·확인 후 답변)
    @Enumerated(EnumType.STRING)
    @Column(name = "response_status", length = 30)
    private AssignmentResponseStatus responseStatus; // 미응답 NULL

    /**
     * 응답 기한 (대상자 응답 마감)
     * 일반 1시간~3일, 급한 요청 30분
     */
    @Column(name = "response_deadline", nullable = false)
    private LocalDateTime responseDeadline; // 시작 시각이 앞당겨지면 limitDeadlineTo()로 줄어듦

    // 최근 응답 일시
    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    // 수락 일시
    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    // 대상 지정 마감 일시 (해당 대상 지정 종료)
    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    // 대상 지정 마감 사유 (변경·관계 해제 등)
    @Enumerated(EnumType.STRING)
    @Column(name = "close_reason", length = 40)
    private AssignmentCloseReason closeReason;

    // 담당자 없음 안내 확인 일시 (요청자가 담당자 없음 모달을 닫은 시각)
    @Column(name = "notice_closed_at")
    private LocalDateTime noticeClosedAt; // 미확인 null

    @Enumerated(EnumType.STRING)
    @Column(name = "notice_reason", length = 30)
    private NoAssigneeReason noticeReason; // 요청자가 모달을 닫을 때의 담당자 없음 사유, 미확인 null


    private RequestAssignment(CareRequest careRequest, Long targetUserId,
                              int assignmentOrder, LocalDateTime responseDeadline) {
        this.careRequest = careRequest;
        this.targetUserId = targetUserId;
        this.assignmentOrder = assignmentOrder;
        this.responseDeadline = responseDeadline;
    }

    // === 생성 메서드 ===
    /**
     * 대상자 지정 (최초 지정, 다시 부탁하기 공통)
     * 응답 기한 계산(1시간~3일, 기본 24시간, 급한 요청 30분, 다시 부탁하기 고정)과
     * 대상자 자격 검증, 지정 순서 계산은 서비스에서 처리함
     */
    public static RequestAssignment assign(CareRequest careRequest, Long targetUserId,
                                           int assignmentOrder, LocalDateTime responseDeadline) {
        // 요청, 대상자, 응답 기한은 필수입니다.
        if(careRequest == null || targetUserId == null || responseDeadline == null) {
            throw new IllegalArgumentException("요청, 대상자, 응답 기한은 필수입니다.");
        }

        // 응답 기한이 시작 시각을 넘지 않는지 검증합니다
        if(!careRequest.isUrgent() && responseDeadline.isAfter(careRequest.getStartsAt())) {
            throw new IllegalArgumentException("응답 기한은 요청 시작 시각을 넘을 수 없습니다.");
        }

        // 수락 철회 후 다시 부탁할 때는 서비스에서 revertToRequested()를 먼저 호출해야 이 검증을 통과합니다.
        // 담당자가 없는 요청(REQUESTED)에만 대상을 지정할 수 있습니다.
        if (careRequest.getStatus() != CareRequestStatus.REQUESTED) {
            throw new IllegalStateException("요청됨 상태에서만 대상자를 지정할 수 있습니다: "
                    + careRequest.getStatus());
        }

        // 요청자 본인을 대상으로 지정할 수 없습니다.
        if (targetUserId.equals(careRequest.getRequesterId())) {
            throw new IllegalArgumentException("요청자 본인을 대상으로 지정할 수 없습니다.");
        }

        return new RequestAssignment(careRequest, targetUserId, assignmentOrder, responseDeadline);
    }

    // ===== 상태 변경 메서드 =====

    /**
     * 대상자 응답 (가능 / 어려움 / 확인 후 답변)
     * - 마감되지 않았으면(대상자 그대로 + 요청 시작 전) 기한이 지나도 응답을 변경 가능
     *   (일반 요청은 기한 경과로 자동 마감하지 않고, 급한 요청은 기한 경과 시 close()로 마감됨)
     * - 가능(AVAILABLE)으로 응답하면 수락으로 기록하고, 담당자 없음 모달 기록을 초기화함 (notice_closed_at을 NULL로)
     * - 요청 상태 변경(careRequest.accept())과 응답 변경 이력 기록은 서비스에서 처리
     */

    public void respond(AssignmentResponseStatus newStatus, LocalDateTime now) {
        // 응답 상태는 필수입니다.
        if (newStatus == null) {
            throw new IllegalArgumentException("응답 상태는 필수입니다.");
        }
        requireOpen();
        requireRespondable(now);

        // 중복 수락을 방지합니다.
        if (isAccepted()) {
            throw new IllegalStateException("이미 수락한 지정입니다. 수락 철회는 close()로 처리합니다.");
        }
        this.responseStatus = newStatus;
        this.respondedAt = now;

        // 가능으로 응답해 실제로 수락된 경우
        if(newStatus == AssignmentResponseStatus.AVAILABLE) {
            this.acceptedAt = now;
            this.noticeClosedAt = null;
            this.noticeReason = null; // 수락되면 다음 담당자 없음 때 다시 표시
        }
    }

    // 스케줄러가 처리하기 직전에 응답이 들어오는 경우를 대비
    private void requireRespondable(LocalDateTime now) {
        if (careRequest.getStatus() != CareRequestStatus.REQUESTED) {
            throw new IllegalStateException("응답할 수 없는 요청입니다: " + careRequest.getStatus());
        }
        if (careRequest.isUrgent()) {
            // 급한 요청: 30분이 지나면 응답 불가
            if (isDeadlinePassed(now)) {
                throw new IllegalStateException("응답 기한이 지난 급한 요청입니다.");
            }
        } else if (!now.isBefore(careRequest.getStartsAt())) {
            // 일반 요청: 기한이 지나도 시작 전이면 응답 가능
            throw new IllegalStateException("시작 시각이 지난 요청에는 응답할 수 없습니다.");
        }
    }

    /**
     * 대상 지정 마감
     * 대상 변경, 수락 철회, 관계 해제, 상황 이탈, 회원 탈퇴, 요청 종료, 급한 요청 기한 경과
     * 이전 지정·응답 이력은 그대로 보존됨
     */
    public void close(AssignmentCloseReason reason, LocalDateTime now) {
        if(reason == null) {
            throw new IllegalArgumentException("마감 사유는 필수입니다.");
        }
        requireOpen();
        this.closedAt = now;
        this.closeReason = reason;
    }

    /** 지금의 담당자 없음 사유. 담당자 없음이 아니면 null */
    public NoAssigneeReason currentNoAssigneeReason(LocalDateTime now) {
        if (!isOpen()) {
            return NoAssigneeReason.from(closeReason);
        }
        if (isAccepted()) {
            return null; // 현재 담당자
        }
        if (responseStatus == AssignmentResponseStatus.UNAVAILABLE) {
            return NoAssigneeReason.UNAVAILABLE;
        }
        return isDeadlinePassed(now) ? NoAssigneeReason.DEADLINE_PASSED : null;
    }

    /** 모달을 띄워야 하는지: 담당자 없음이고, 아직 이 사유로 닫은 적이 없음 (F-14 사유마다 1회) */
    public boolean needsNoAssigneeNotice(LocalDateTime now) {
        NoAssigneeReason reason = currentNoAssigneeReason(now);
        return reason != null && reason != noticeReason;
    }

    /**
     * 담당자 없음 모달 닫음 기록 (F-14, 기존 closeNotice 교체)
     * 화면에 보여준 사유를 받습니다.
     * 모달을 보는 사이에 사유가 바뀌어도, 새 사유 모달이 한 번 더 뜨게 하기 위해서 입니다.
     */
    public void closeNotice(NoAssigneeReason shownReason, LocalDateTime now) {
        if(this.noticeClosedAt == null) {
            throw new IllegalArgumentException("모달에 표시한 사유는 필수입니다.");
        }
        this.noticeReason = shownReason;
        this.noticeClosedAt = now;
    }

    // 요청 시작 시각이 앞당겨지면 응답 기한이 새 시작 시각을 넘지 않도록 줄입니다
    public void limitDeadlineTo(LocalDateTime newStartsAt) {
        if (newStartsAt == null) {
            return; // 급한 요청은 시작 시각이 없으므로 줄이지 않음
        }
        if (isOpen() && responseDeadline.isAfter(newStartsAt)) {
            this.responseDeadline = newStartsAt;
        }
    }

    // === 조회용 메서드 ===
    /** 마감되지 않은 현재 유효한 지정인지 */
    public boolean isOpen() {
        return closedAt == null;
    }

    /** 가능 응답으로 수락한 적이 있는지 (마감 여부와 무관한 이력) */
    public boolean isAccepted() {
        return acceptedAt != null;
    }

    /** 현재 담당자인지 (수락했고 아직 마감되지 않음) */
    public boolean isCurrentAssignee() {
        return isOpen() && isAccepted();
    }

    /** 응답 기한이 지났는지 */
    public boolean isDeadlinePassed(LocalDateTime now) {
        return now.isAfter(responseDeadline);
    }

    // ===== 내부 검증 =====
    // TODO: B의 공통 에러 코드가 병합되면 팀 공통 예외로 교체

    private void requireOpen() {
        if (!isOpen()) {
            throw new IllegalStateException("이미 마감된 대상 지정입니다.");
        }
    }
}
