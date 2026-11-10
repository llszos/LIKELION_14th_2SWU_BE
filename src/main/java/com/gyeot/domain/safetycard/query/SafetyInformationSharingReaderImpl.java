package com.gyeot.domain.safetycard.query;

import com.gyeot.domain.access.entity.PermissionStatus;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import com.gyeot.domain.safetycard.exception.SafetyCardErrorCode;
import com.gyeot.domain.safetycard.repository.SafetyInformationRepository;
import com.gyeot.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * SafetyInformationSharingReader(안심카드 공개 항목 조회 인터페이스)의 실제 구현입니다.
 * 사용하는 쪽(C 요청 등)은 이 구현 클래스가 아니라 SafetyInformationSharingReader 타입으로 선언합니다.
 */
@Service                         // Spring이 객체로 만들어 SafetyInformationSharingReader를 주입받는 곳에 넣어 줌
@RequiredArgsConstructor         // 아래 final 필드(Repository 2개)를 채우는 생성자 자동 생성
@Transactional(readOnly = true)  // 조회만 하므로 읽기 전용 트랜잭션으로 실행
public class SafetyInformationSharingReaderImpl implements SafetyInformationSharingReader {

    // 공개 중으로 보는 권한 상태 (수락 전 + 수락 후)
    private static final List<PermissionStatus> SHARING_STATUSES = List.of(PermissionStatus.PENDING, PermissionStatus.ACTIVE);

    private final SafetyInformationRepository safetyInformationRepository; // 항목 존재·주인 확인
    private final RequestInformationPermissionRepository permissionRepository; // 요청의 공개 중 권한 조회

    @Override
    public void validateSelection(Long ownerUserId, List<Long> informationIds) {
        if (informationIds == null || informationIds.isEmpty()) {
            return; // 공개 항목 0개 요청 허용
        }

        // null이나 같은 ID가 두 번 들어오면 FE가 잘못 보낸 것이므로 거부
        Set<Long> uniqueIds = new HashSet<>(informationIds);
        if (uniqueIds.contains(null) || uniqueIds.size() != informationIds.size()) {
            throw invalidSelection();
        }

        // 하나라도 없거나(삭제 포함) 다른 사람 항목이면 전체 거부 (일부만 빼면 요청자 모르게 공개 항목이 달라짐)
        List<SafetyInformation> found = safetyInformationRepository.findAllById(uniqueIds);
        boolean allOwned = found.size() == uniqueIds.size()
                && found.stream().allMatch(information -> Objects.equals(information.getOwnerId(), ownerUserId));
        if (!allOwned) {
            throw invalidSelection();
        }
    }

    @Override
    public List<Long> findSharingInformationIds(Long requestId) {
        return permissionRepository.findInformationIdsByRequestIdAndStatusIn(requestId, SHARING_STATUSES);
    }

    private BusinessException invalidSelection() {
        return new BusinessException(SafetyCardErrorCode.SAFETY_INFO_INVALID_SELECTION);
    }
}
