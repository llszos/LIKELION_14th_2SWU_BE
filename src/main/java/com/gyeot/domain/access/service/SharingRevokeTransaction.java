package com.gyeot.domain.access.service;

import com.gyeot.domain.access.dto.response.SharingRevokeResponse;
import com.gyeot.domain.access.dto.response.SharingStatus;
import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.access.event.PermissionRevokedEvent;
import com.gyeot.domain.access.service.SharingCardLocker.LockedCard;
import com.gyeot.domain.access.service.SharingCardRules.ItemJudgement;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader.SharingRequestInfo;
import com.gyeot.domain.safetycard.exception.SafetyCardErrorCode;
import com.gyeot.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 공유 직접 철회의 쓰기 트랜잭션입니다. (노션 D 파트 API 명세 '공유 직접 철회', F-30, F-09, #46)
 * SharingRevokeService가 요청 ID를 확보한 뒤 이 빈을 부르며, 카드 판단부터 회수까지 한 트랜잭션에서 처리합니다.
 *
 * 처리 순서 (잠금 순서 C 요청 → 항목 → 권한, #38)
 * 0. C 요청 행 잠금: CareRequestSharingReader.findByIdForUpdate (#124). 이 트랜잭션의 첫 조회입니다.
 *    - C가 요청 상태나 지정을 바꾸는 작업도 같은 요청 행을 먼저 잠급니다. (#124 계약)
 *      그래서 그 작업과 같은 카드의 다른 철회, 다시 허용이 이 잠금에서 차례로 실행됩니다.
 *    - 잠금 조회는 스냅샷을 만들지 않아, 잠금을 얻은 뒤의 첫 일반 조회에서 스냅샷이 만들어집니다.
 *      그래서 뒤의 조회에는 먼저 끝난 쪽의 변경이 보입니다. (그 전에 다른 일반 조회를 하면 안 됨)
 *    - 항목 삭제는 요청 잠금을 거치지 않으므로, 항목과 권한은 2~4에서 따로 잠급니다.
 * 1. 요청 조건: 0에서 읽은 요청으로 SharingCardRules.isOpenSharing. 통과하지 못하면 404 (끝난 요청, 다른 사람의 공유, 담당자 변경 등)
 * 2~4. 항목과 권한 잠금: SharingCardLocker (항목 ID만 조회 → 항목 잠금 → 권한 잠금. 다시 허용과 같은 단계)
 * 5. 판단: SharingCardRules의 최신 권한, 원본 확인 뒤 상태 결정 (공유 중 목록과 같은 규칙)
 *    - SHARING: 보여 주던 ACTIVE 권한을 모두 OWNER_REVOKED로 회수하고, 실제로 회수했으면 담당자 알림 이벤트를 1건 발행
 *    - REVOKED: 이미 철회된 카드라 아무것도 바꾸지 않음 (200, 동시 철회에서 늦은 쪽 포함)
 *    - 빈 카드(남은 항목 없음): 404 (항목이 삭제된 권한 이력은 철회에서는 쓰지 않음. 다시 허용의 오류 구분용)
 *
 * - 조회용 SharingCardReader의 카드 판단을 쓰지 않습니다. 잠금 전에 권한 엔티티를 읽으면, 뒤의 잠금 조회가
 *   영속성 컨텍스트에 있던 예전 객체를 그대로 돌려줘 최신 상태로 판단하지 못하기 때문입니다. 규칙(SharingCardRules)만 같이 씁니다.
 *   같은 이유로 1에서 권한 엔티티를 읽지 않습니다. (C 요청 정보만 조회)
 * - 회수 뒤 카드를 일반 조회로 다시 판단하지 않습니다. 스냅샷 뒤에 커밋된 변경(동시 삭제 등)이 보이지 않을 수 있어,
 *   잠금으로 확보한 값으로 응답을 정합니다.
 * - 응답의 canReallow는 관계(A)와 상황 참여(B)를 일반 조회로 확인합니다. 버튼 표시용이고, 다시 허용 API가 잠금 뒤 다시 확인합니다.
 */
@Component               // SharingRevokeService가 주입받아 씀 (다른 빈이라 아래 트랜잭션 설정이 적용됨)
@RequiredArgsConstructor // 아래 final 필드를 채우는 생성자 자동 생성
@Transactional           // 쓰기 트랜잭션. SharingRevokeService가 트랜잭션 없이 부르므로 항상 새로 시작
public class SharingRevokeTransaction {

    private final CareRequestSharingReader careRequestSharingReader;          // C: 요청 행 잠금, 요청 상태, 지금 담당자
    private final SharingCardLocker sharingCardLocker;                        // 항목, 권한 잠금 (다시 허용과 공통)
    private final PermissionWriter permissionWriter;                          // 회수와 이력
    private final SharingCardReader sharingCardReader;                        // canReallow 계산 (관계, 상황 참여)
    private final ApplicationEventPublisher eventPublisher;                   // 권한 종료 이벤트 발행 (D → B 알림)
    private final Clock clock;                                                // 지금 시각 (서비스 시간대 Asia/Seoul, 테스트에서 고정 가능)

    /**
     * @param ownerUserId  로그인한 사용자 (정보 주인)
     * @param assignmentId 공유 카드 ID
     * @param requestId    카드가 속한 요청 ID (SharingRevokeService가 트랜잭션 밖에서 조회)
     * @return 철회 뒤 카드 상태 (REVOKED, canReallow)
     */
    public SharingRevokeResponse revoke(Long ownerUserId, Long assignmentId, Long requestId) {

        // 0. C 요청 행 잠금 (이 트랜잭션의 첫 조회. 같은 요청을 바꾸는 다른 트랜잭션이 있으면 끝날 때까지 기다림)
        //    요청이 없으면 빈 Optional이라 아래 요청 조건에서 404
        SharingRequestInfo request = careRequestSharingReader.findByIdForUpdate(requestId).orElse(null);

        LocalDateTime now = LocalDateTime.now(clock); // 잠금을 얻은 뒤의 시각. 요청 조건 비교와 철회 시각에 같은 값 사용

        // 1. 요청 조건 (목록과 같은 규칙). 끝났거나 다른 사람의 공유면 카드가 없는 것과 같은 404
        if (!SharingCardRules.isOpenSharing(request, ownerUserId, assignmentId, null, now)) {
            throw notFound();
        }

        // 2~4. 항목을 잠근 뒤 권한을 잠금 (잠근 원본과 최신 권한을 돌려받음)
        LockedCard card = sharingCardLocker.lock(ownerUserId, assignmentId);

        // 5. 최신 권한과 원본으로 상태를 정함. 남은 항목이 없으면 목록에서도 빠진 카드라 404
        ItemJudgement judged = card.judge().orElseThrow(this::notFound);

        if (judged.status() == SharingStatus.SHARING) {
            // 보여 주던 ACTIVE 권한을 모두 철회 (섞인 카드의 직접 철회 항목은 이미 REVOKED라 그대로)
            List<RequestInformationPermission> revoked =
                    permissionWriter.revokeByOwner(judged.permissions(), ownerUserId, now);

            if (!revoked.isEmpty()) {
                // 항목 수와 상관없이 카드(요청, 담당자)당 1건. 받는 사람 = 지금 담당자 (1에서 이 지정의 담당자임을 확인함)
                eventPublisher.publishEvent(new PermissionRevokedEvent(
                        request.requestId(), assignmentId, ownerUserId, request.assigneeId(), RevokeReason.OWNER_REVOKED));
            }
        } // REVOKED면 이미 철회된 카드라 바꾸지 않음 (재철회, 동시 철회의 늦은 쪽)

        // 철회 뒤 카드는 직접 철회 항목이 남은 REVOKED. 다시 허용 버튼은 관계와 상황 참여로 정함
        return SharingRevokeResponse.revoked(assignmentId, sharingCardReader.canReopen(request, ownerUserId));
    }

    private BusinessException notFound() {
        return new BusinessException(SafetyCardErrorCode.SHARING_NOT_FOUND);
    }
}
