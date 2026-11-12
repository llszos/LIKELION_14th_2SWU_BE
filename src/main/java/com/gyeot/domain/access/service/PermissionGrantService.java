package com.gyeot.domain.access.service;

import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.carerequest.enums.RequestType;
import com.gyeot.domain.carerequest.event.CareRequestAcceptedEvent;
import com.gyeot.domain.carerequest.event.CareRequestCreatedEvent;
import com.gyeot.domain.carerequest.event.CareRequestTargetChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * C 요청 이벤트에 따라 안심카드 접근 권한을 만들고 활성화합니다. (F-03, F-09, ERD 9.2, #42)
 *
 * - 이벤트를 발행한 C 트랜잭션 안에서만 실행합니다. (MANDATORY)
 *   트랜잭션 없이 호출되면 C 변경과 권한 변경이 따로 저장될 수 있어 바로 실패시킵니다.
 * - 같은 요청의 C 이벤트는 요청 행 잠금(findByIdForUpdate)을 획득한 트랜잭션 안에서 동기로 발행되는 것을 전제로 합니다.
 *   그래서 권한이 아직 없는 상태에서도 같은 요청의 이벤트가 동시에 처리되지 않습니다.
 * - 정보 주인은 요청자입니다. (requesterId)
 * - GRANTED 이력의 수행자는 권한이 열리게 한 사람입니다. 수락이면 담당자입니다. (시스템 자동 처리만 NULL, ERD 4.12)
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class PermissionGrantService {

    static final long URGENT_ACCESS_HOURS = 24; // 급한 요청 열람 기한: 수락 후 24시간 (F-13, ERD 4.11)

    private final PermissionWriter permissionWriter;

    /**
     * 요청 생성: 최초 지정에 공개 항목마다 PENDING 권한을 만듭니다.
     * 공개 항목이 0개인 요청은 권한을 만들지 않습니다.
     */
    public void grantOnRequestCreated(CareRequestCreatedEvent event) {
        // 정보 주인 = 요청자. 대상자는 아직 수락 전이라 PENDING (내용은 볼 수 없음)
        permissionWriter.createPending(event.requestId(), event.assignmentId(),
                event.requesterId(), event.informationIds());
    }

    /**
     * 수락: 담당자의 공유 예정 권한을 모두 활성화하고 항목마다 GRANTED 이력을 남깁니다.
     * - 급한 요청은 expires_at = 수락 시각 + 24시간, 일반 요청은 기한 없이 요청 종료 시각으로 판단합니다.
     * - 공개 항목이 0개인 요청은 바꿀 권한이 없어 아무 일도 하지 않습니다.
     * - expires_at을 저장하는 것만으로 접근이 막히지 않습니다. 전달받은 정보 조회(#45)에서 기한을 함께 검사합니다.
     * - 급한 요청(F-13)은 SHOULD 범위라 여기서는 기한 저장까지만 합니다. 24시간이 지난 권한의 자동 회수는 이번 범위가 아닙니다.
     */
    public void grantOnAccepted(CareRequestAcceptedEvent event) {

        // 급한 요청만 기한을 둠. 일반 요청은 null이고 요청 종료 시각(C의 endsAt)으로 판단
        LocalDateTime expiresAt = event.requestType() == RequestType.URGENT
                ? event.occurredAt().plusHours(URGENT_ACCESS_HOURS)
                : null;

        // 정보 주인 = 요청자, 수행자 = 수락한 담당자, 부여 시각 = 수락 시각
        permissionWriter.activatePending(event.assignmentId(), event.requesterId(), event.assigneeId(),
                event.occurredAt(), expiresAt);
    }

    /**
     * 대상 변경(다시 부탁하기): 이전 지정의 권한을 끝내고, 새 지정에 공유 예정 권한을 만듭니다. (ERD 9.2)
     * - 이전 지정은 ASSIGNMENT_CLOSED로 회수합니다. 보통 수락 전(PENDING)이라 이력은 남지 않습니다.
     * - 새 지정은 이벤트의 informationIds로 만듭니다. 다시 부탁하기 화면에서 공개 항목을 바꿀 수 있어,
     *   이전 지정의 항목을 복사하지 않습니다.
     * - 같은 이벤트가 다시 와도 회수는 건너뛰고, 생성은 이미 있는 항목을 만들지 않아 결과가 같습니다.
     */
    public void grantOnTargetChanged(CareRequestTargetChangedEvent event) {

        // 1. 이전 지정 회수 (C가 이전 지정을 먼저 마감한 뒤 발행하므로 이 지정은 더 쓰이지 않음)
        permissionWriter.revokeAssignment(event.previousAssignmentId(), RevokeReason.ASSIGNMENT_CLOSED,
                event.requesterId(), event.occurredAt());

        // 2. 새 지정에 이번에 고른 항목으로 PENDING 생성 (새 대상자는 아직 수락 전)
        permissionWriter.createPending(event.requestId(), event.assignmentId(),
                event.requesterId(), event.informationIds());
    }
}
