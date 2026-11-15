package com.gyeot.domain.access.service;

import com.gyeot.domain.access.dto.response.SharingReallowResponse;
import com.gyeot.domain.access.dto.response.SharingStatus;
import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.access.service.SharingCardLocker.LockedCard;
import com.gyeot.domain.access.service.SharingCardRules.ItemJudgement;
import com.gyeot.domain.carerequest.enums.RequestType;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader.SharingRequestInfo;
import com.gyeot.domain.safetycard.exception.SafetyCardErrorCode;
import com.gyeot.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 공유 다시 허용의 쓰기 트랜잭션입니다. (노션 D 파트 API 명세 '공유 다시 허용', F-30, F-09, #46)
 * SharingReallowService가 요청 ID를 확보한 뒤 이 빈을 부르며, 판단부터 권한 생성까지 한 트랜잭션에서 처리합니다.
 *
 * 처리 순서 (잠금 순서 C 요청 → 항목 → 권한, #38)
 * 0. C 요청 행 잠금: C의 잠금 계약이 들어온 뒤 이 자리에 추가합니다. 트랜잭션의 첫 조회여야 합니다.
 *    아직 없으므로 지금은 최종 동시성 보장 전 상태입니다. (같은 요청의 C 변경과 직렬화되지 않음)
 * 1. 카드 확인: 요청이 없거나, 정보 주인의 요청이 아니거나, 급한 요청이면 404 (공유 관리에 카드로 나오지 않는 경우)
 * 2. 요청 조건: SharingCardRules.isOpenSharing. 통과하지 못하면 409 SHARING_REALLOW_NOT_ALLOWED
 *    (수락됨, 진행 중이 아님, 종료 시각 지남, 담당자 변경)
 * 3. 다시 열 수 있는 상태: 요청자 → 담당자 관계와 담당자의 상황 참여. 아니면 409 SHARING_REALLOW_NOT_ALLOWED
 *    (목록의 canReallow와 같은 기준. 관계 해제, 상황 나가기)
 * 4. 항목과 권한 잠금: SharingCardLocker (철회와 같은 단계)
 * 5. 판단: SharingCardRules의 최신 권한, 원본 확인 뒤 상태 결정 (공유 중 목록과 같은 규칙)
 *    - REVOKED: 직접 철회한 항목마다 새 ACTIVE 권한을 만들고 GRANTED 이력을 남김 (기존 REVOKED 행은 보존)
 *    - SHARING: 이미 공유 중이라 409 SHARING_REALLOW_NOT_ALLOWED (섞인 카드 포함)
 *    - 빈 카드: 아래 오류 구분 규칙
 *
 * 빈 카드의 오류 구분
 * - 유효한 항목이 하나도 없을 때, 항목이 삭제된 권한 이력(information_id NULL)에 직접 철회 기록이 있으면
 *   "철회한 항목이 모두 삭제됨"으로 보고 409 SHARING_NOTHING_TO_REALLOW, 없으면 409 SHARING_REALLOW_NOT_ALLOWED입니다.
 * - 한계: 삭제된 항목의 기록은 information_id가 비어 있어 어느 항목의 기록인지 알 수 없고, 그 항목의 최신 권한도 알 수 없습니다.
 *   예를 들어 직접 철회 → 다시 허용 → 공개 항목 제외 뒤 삭제한 항목은 NOT_ALLOWED가 맞지만, 남은 직접 철회 기록 때문에
 *   NOTHING_TO_REALLOW로 판단될 수 있습니다. 둘 다 409이고 FE 처리(목록 다시 불러오기)가 같아 이 근사를 씁니다.
 *
 * - 섞인 카드: 철회 뒤 요청 수정으로 새 항목이 추가돼 ACTIVE가 생기면 카드는 SHARING이라 다시 허용은 409입니다.
 *   이때 전에 철회한 항목을 다시 열려면 카드 전체를 철회한 뒤 다시 허용해야 하며, 그러면 전에 철회한 항목도 함께 열립니다.
 * - 다시 허용은 담당자 알림 이벤트를 발행하지 않습니다. (명세에 알림 없음, GRANTED 이력만 남김)
 * - 조회용 SharingCardReader의 카드 판단을 쓰지 않는 이유와 잠금 전 권한 엔티티를 읽지 않는 전제는 SharingRevokeTransaction과 같습니다.
 */
@Component               // SharingReallowService가 주입받아 씀 (다른 빈이라 아래 트랜잭션 설정이 적용됨)
@RequiredArgsConstructor // 아래 final 필드를 채우는 생성자 자동 생성
@Transactional           // 쓰기 트랜잭션. SharingReallowService가 트랜잭션 없이 부르므로 항상 새로 시작
public class SharingReallowTransaction {

    private final CareRequestSharingReader careRequestSharingReader; // C: 요청 상태, 지금 담당자
    private final SharingCardReader sharingCardReader;               // 다시 열 수 있는 상태 (관계, 상황 참여)
    private final SharingCardLocker sharingCardLocker;               // 항목, 권한 잠금 (철회와 공통)
    private final PermissionWriter permissionWriter;                 // 새 ACTIVE 권한과 GRANTED 이력
    private final Clock clock;                                       // 지금 시각 (서비스 시간대 Asia/Seoul, 테스트에서 고정 가능)

    /**
     * @param ownerUserId  로그인한 사용자 (정보 주인)
     * @param assignmentId 공유 카드 ID
     * @param requestId    카드가 속한 요청 ID (SharingReallowService가 트랜잭션 밖에서 조회)
     * @return 다시 허용 뒤 카드 상태 (SHARING, 다시 공개된 항목 작성순)
     */
    public SharingReallowResponse reallow(Long ownerUserId, Long assignmentId, Long requestId) {

        // 0. C 요청 행 잠금 자리 (C 잠금 계약 반영 시 이 메서드의 첫 조회로 추가)

        LocalDateTime now = LocalDateTime.now(clock); // 요청 조건 비교와 권한 부여 시각에 같은 값 사용

        // 1. 공유 관리에 나오는 카드인지 (아니면 카드가 없는 것과 같은 404)
        SharingRequestInfo request = careRequestSharingReader.findById(requestId).orElse(null);
        if (request == null
                || !Objects.equals(request.requesterId(), ownerUserId)  // 다른 사람의 공유
                || request.requestType() != RequestType.GENERAL) {     // 급한 요청은 이번 범위에서 카드가 아님
            throw error(SafetyCardErrorCode.SHARING_NOT_FOUND);
        }

        // 2. 요청 조건 (목록과 같은 규칙). 카드는 있지만 지금은 다시 열 수 없는 상태라 409
        if (!SharingCardRules.isOpenSharing(request, ownerUserId, assignmentId, null, now)) {
            throw error(SafetyCardErrorCode.SHARING_REALLOW_NOT_ALLOWED);
        }

        // 3. 관계와 상황 참여 (목록의 canReallow와 같은 기준이라 버튼 표시와 실제 동작이 어긋나지 않음)
        if (!sharingCardReader.canReopen(request, ownerUserId)) {
            throw error(SafetyCardErrorCode.SHARING_REALLOW_NOT_ALLOWED);
        }

        // 4. 항목을 잠근 뒤 권한을 잠금 (잠근 원본, 최신 권한, 삭제 이력을 돌려받음)
        LockedCard card = sharingCardLocker.lock(ownerUserId, assignmentId);

        // 5. 최신 권한과 원본으로 상태를 정함
        Optional<ItemJudgement> judged = card.judge();
        if (judged.isEmpty()) {
            throw error(emptyCardError(card.deletedHistory())); // 빈 카드: 철회한 항목이 모두 삭제됐는지로 구분
        }
        if (judged.get().status() == SharingStatus.SHARING) {
            throw error(SafetyCardErrorCode.SHARING_REALLOW_NOT_ALLOWED); // 이미 공유 중 (섞인 카드 포함)
        }

        // REVOKED: 직접 철회한 항목(원본이 남은 것만)마다 새 ACTIVE 권한과 GRANTED 이력 (기존 REVOKED 행은 이력으로 보존)
        // 항목 잠금을 쥔 상태라 createActive 안의 항목 확인(잠금 조회)은 기다리지 않고, 그 사이 삭제될 수 없음
        List<Long> informationIds = judged.get().permissions().stream()
                .map(RequestInformationPermission::getInformationId)
                .toList(); // 작성순
        permissionWriter.createActive(request.requestId(), assignmentId, ownerUserId,
                ownerUserId,      // 수행자 = 정보 주인 (다시 허용한 사람)
                informationIds,
                now,              // 부여 시각
                null);            // 일반 요청이라 접근 기한 없음 (요청 종료 시각으로 판단)

        return SharingReallowResponse.reallowed(assignmentId, judged.get().names()); // 다시 공개된 항목 (작성순)
    }

    /**
     * 빈 카드의 오류 코드를 정합니다. (클래스 설명의 '빈 카드의 오류 구분')
     *
     * @param deletedHistory 항목이 삭제된 권한 이력 (information_id NULL)
     * @return 직접 철회 기록이 있으면 SHARING_NOTHING_TO_REALLOW, 없으면 SHARING_REALLOW_NOT_ALLOWED
     */
    private static SafetyCardErrorCode emptyCardError(List<RequestInformationPermission> deletedHistory) {
        boolean revokedItemDeleted = deletedHistory.stream()
                .anyMatch(permission -> permission.getRevokeReason() == RevokeReason.OWNER_REVOKED);
        return revokedItemDeleted
                ? SafetyCardErrorCode.SHARING_NOTHING_TO_REALLOW   // 철회한 항목이 모두 삭제됨
                : SafetyCardErrorCode.SHARING_REALLOW_NOT_ALLOWED; // 직접 철회한 공유가 아님 (공개 제외, 다른 회수 사유 등)
    }

    private static BusinessException error(SafetyCardErrorCode errorCode) {
        return new BusinessException(errorCode);
    }
}
