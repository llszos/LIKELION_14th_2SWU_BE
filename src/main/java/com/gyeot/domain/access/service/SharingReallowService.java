package com.gyeot.domain.access.service;

import com.gyeot.domain.access.dto.response.SharingReallowResponse;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.safetycard.exception.SafetyCardErrorCode;
import com.gyeot.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공유 다시 허용의 진입점입니다. (노션 D 파트 API 명세 '공유 다시 허용', F-30, #46)
 * 요청 ID를 먼저 확보하고, 실제 다시 허용은 SharingReallowTransaction의 새 트랜잭션에서 처리합니다.
 *
 * - 두 단계로 나눈 이유와 트랜잭션 경계(NOT_SUPPORTED, 빈 분리)는 SharingRevokeService와 같습니다.
 *   쓰기 트랜잭션이 C 요청 행 잠금을 첫 조회로 할 수 있게, 잠금에 필요한 요청 ID를 트랜잭션 밖에서 미리 조회합니다.
 * - API 진입 전용이며, 다른 트랜잭션 안에서 다시 허용을 함께 처리하는 용도가 아닙니다.
 * - 요청 ID가 없으면(권한이 없는 지정) 404입니다. 다른 사람의 공유인지는 쓰기 트랜잭션에서 확인하며, 그때도 같은 404입니다.
 */
@Service                                               // SharingController가 주입받아 씀
@RequiredArgsConstructor                               // 아래 final 필드를 채우는 생성자 자동 생성
@Transactional(propagation = Propagation.NOT_SUPPORTED) // 트랜잭션 없이 실행 (SharingRevokeService 설명과 같음)
public class SharingReallowService {

    private final RequestInformationPermissionRepository permissionRepository; // 지정 → 요청 ID (읽기 전용 트랜잭션 명시)
    private final SharingReallowTransaction reallowTransaction;                 // 실제 다시 허용 (새 쓰기 트랜잭션)

    /**
     * @param ownerUserId  로그인한 사용자 (정보 주인)
     * @param assignmentId 공유 카드 ID
     * @return 다시 허용 뒤 카드 상태 (SHARING, 다시 공개된 항목)
     */
    public SharingReallowResponse reallow(Long ownerUserId, Long assignmentId) {
        Long requestId = permissionRepository.findRequestIdByAssignmentId(assignmentId)
                .orElseThrow(() -> new BusinessException(SafetyCardErrorCode.SHARING_NOT_FOUND)); // 권한이 없는 지정
        return reallowTransaction.reallow(ownerUserId, assignmentId, requestId); // 다른 빈 호출이라 새 트랜잭션이 적용됨
    }
}
