package com.gyeot.domain.access.service;

import com.gyeot.domain.access.dto.response.SharingRevokeResponse;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.safetycard.exception.SafetyCardErrorCode;
import com.gyeot.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공유 직접 철회의 진입점입니다. (노션 D 파트 API 명세 '공유 직접 철회', F-30, #46)
 * 요청 ID를 먼저 확보하고, 실제 철회는 SharingRevokeTransaction의 새 트랜잭션에서 처리합니다.
 *
 * - 두 단계로 나눈 이유: 쓰기 트랜잭션은 C 요청 행 잠금을 첫 조회로 해야 합니다. (REPEATABLE READ의 스냅샷은 첫 일반 조회 때 만들어짐)
 *   그런데 API는 assignmentId만 받아, 잠금에 필요한 요청 ID를 알려면 먼저 조회해야 합니다.
 *   이 조회를 쓰기 트랜잭션 밖의 짧은 읽기 전용 트랜잭션에서 끝내, 쓰기 트랜잭션에 잠금 전 스냅샷이 남지 않게 합니다.
 * - 트랜잭션 경계는 빈으로 나눕니다. 같은 클래스 안의 메서드 호출은 프록시를 거치지 않아 트랜잭션 설정이 적용되지 않기 때문입니다.
 * - NOT_SUPPORTED: 호출한 쪽에 트랜잭션이 있어도 잠시 멈추고 실행합니다. 그래서 요청 ID 조회는 저장소 메서드의 읽기 전용 트랜잭션으로,
 *   SharingRevokeTransaction은 항상 새 트랜잭션으로 시작합니다. API 진입 전용이며, 다른 트랜잭션 안에서 철회를 함께 처리하는 용도가 아닙니다.
 * - 요청 ID가 없으면(권한이 없는 지정) 404입니다. 다른 사람의 공유인지는 쓰기 트랜잭션에서 확인하며, 그때도 같은 404입니다.
 */
@Service                                               // SharingController가 주입받아 씀
@RequiredArgsConstructor                               // 아래 final 필드를 채우는 생성자 자동 생성
@Transactional(propagation = Propagation.NOT_SUPPORTED) // 트랜잭션 없이 실행 (위 설명)
public class SharingRevokeService {

    private final RequestInformationPermissionRepository permissionRepository; // 지정 → 요청 ID (읽기 전용 트랜잭션 명시)
    private final SharingRevokeTransaction revokeTransaction;                   // 실제 철회 (새 쓰기 트랜잭션)

    /**
     * @param ownerUserId  로그인한 사용자 (정보 주인)
     * @param assignmentId 공유 카드 ID
     * @return 철회 뒤 카드 상태 (REVOKED, canReallow)
     */
    public SharingRevokeResponse revoke(Long ownerUserId, Long assignmentId) {
        Long requestId = permissionRepository.findRequestIdByAssignmentId(assignmentId)
                .orElseThrow(() -> new BusinessException(SafetyCardErrorCode.SHARING_NOT_FOUND)); // 권한이 없는 지정
        return revokeTransaction.revoke(ownerUserId, assignmentId, requestId); // 다른 빈 호출이라 새 트랜잭션이 적용됨
    }
}
