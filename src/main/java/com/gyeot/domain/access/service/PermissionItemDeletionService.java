package com.gyeot.domain.access.service;

import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.access.event.PermissionRevokedEvent;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader.SharingRequestInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 안심카드 항목을 삭제할 때 연결된 접근 권한을 정리합니다. (F-02, F-09, ERD 9.5, #38)
 *
 * - 항목 삭제(SafetyInformationService.delete)의 트랜잭션 안에서만 실행합니다. (MANDATORY)
 *   권한 회수, 이력, information_id 정리, 항목 삭제가 하나라도 실패하면 모두 되돌립니다.
 * - 호출하는 쪽이 삭제할 항목 행을 먼저 잠근 상태여야 합니다. (SafetyInformationService.getOwnedItem, #38 잠금 규칙)
 *   그래서 이 항목으로 권한을 만들거나 바꾸는 다른 작업은 끼어들지 못하고, 아래에서 읽는 권한도 잠금 조회라 최신 상태입니다.
 * - 처리 순서 (ERD 9.5)
 *   1. 그 항목의 PENDING, ACTIVE 권한을 ITEM_DELETED로 회수하고, ACTIVE였던 권한만 AUTO_REVOKED 이력 (수행자: 정보 주인)
 *   2. ACTIVE였던 권한의 (요청, 담당자) 묶음마다 PermissionRevokedEvent 1건 발행 (담당자 알림, B)
 *   3. 그 항목을 가리키던 모든 권한의 information_id를 NULL로 (information_type과 이력은 유지)
 *   항목 행 삭제는 호출한 쪽(SafetyInformationService)이 이어서 합니다.
 *
 * - 정보 주인이 혼자 끝낸 경우라 C 이벤트가 없으므로, 담당자 알림용 이벤트를 D가 발행합니다. (D 요구사항명세 F-09)
 * - 회원 탈퇴로 인한 항목 삭제는 알리지 않습니다. 그 처리는 #49(SHOULD 1차)에서 따로 합니다.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class PermissionItemDeletionService {

    private final PermissionWriter permissionWriter;
    private final CareRequestSharingReader careRequestSharingReader; // 알림 받을 담당자 ID 조회 (권한 행에는 담당자 ID가 없음)
    private final ApplicationEventPublisher eventPublisher;          // 권한 종료 이벤트 발행 (D → B)

    /**
     * 삭제할 항목의 권한을 회수하고, 담당자 알림 이벤트를 발행하고, information_id를 비웁니다.
     *
     * @param ownerUserId   정보 주인 ID (항목 주인, 본인 확인은 호출한 쪽에서 끝남)
     * @param informationId 삭제할 항목 ID
     * @param deletedAt     삭제 시각 (회수 시각, 이력 시각)
     */
    public void revokeOnItemDeleted(Long ownerUserId, Long informationId, LocalDateTime deletedAt) {

        // 1. 회수 (ACTIVE였던 권한만 돌려받음, 알림 대상)
        List<RequestInformationPermission> wasActive = permissionWriter.revokeItem(informationId, ownerUserId, deletedAt);

        // 2. (요청, 담당자) 묶음마다 이벤트 1건 (항목 하나를 지워도 공유 카드 기준으로 알림)
        publishRevokedEvents(ownerUserId, wasActive);

        // 3. 원본이 사라지므로 권한이 가리키던 항목 ID를 비움 (과거 권한 포함)
        permissionWriter.clearInformationId(informationId);
    }

    private void publishRevokedEvents(Long ownerUserId, List<RequestInformationPermission> wasActive) {
        if (wasActive.isEmpty()) {
            return; // 공유 중이 아니었거나 모두 수락 전(PENDING)이라 알릴 사람 없음
        }

        // 같은 지정의 권한은 하나로 묶음. 지정 하나 = (요청, 담당자) 묶음 하나
        // 한 항목은 지정마다 권한이 하나라 보통 겹치지 않지만, 묶음당 1건 계약을 지키려고 지정 기준으로 모음
        Map<Long, Long> requestIdByAssignmentId = new LinkedHashMap<>(); // 발행 순서를 조회 순서대로 고정
        for (RequestInformationPermission permission : wasActive) {
            requestIdByAssignmentId.putIfAbsent(permission.getAssignmentId(), permission.getRequestId());
        }

        // 담당자 ID는 C에서 요청 단위로 한 번에 조회 (요청마다 따로 조회하지 않음)
        Map<Long, SharingRequestInfo> requests =
                careRequestSharingReader.findAllByIds(requestIdByAssignmentId.values());

        requestIdByAssignmentId.forEach((assignmentId, requestId) -> {
            SharingRequestInfo request = requests.get(requestId);

            // ACTIVE 권한은 항상 지금 담당자의 지정이라 C의 현재 지정과 같아야 함
            // 다르거나 요청이 없으면 알릴 담당자를 확정할 수 없어 건너뜀 (회수와 삭제는 그대로 진행)
            if (request == null || !Objects.equals(request.assignmentId(), assignmentId) || request.assigneeId() == null) {
                return;
            }

            eventPublisher.publishEvent(new PermissionRevokedEvent(
                    requestId, assignmentId, ownerUserId, request.assigneeId(), RevokeReason.ITEM_DELETED));
        });
    }
}
