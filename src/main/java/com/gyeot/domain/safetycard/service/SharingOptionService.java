package com.gyeot.domain.safetycard.service;

import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.safetycard.dto.response.SharingOptionsResponse;
import com.gyeot.domain.safetycard.dto.response.SharingOptionsResponse.CategoryOption;
import com.gyeot.domain.safetycard.dto.response.SharingOptionsResponse.ItemOption;
import com.gyeot.domain.safetycard.entity.InformationCategory;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import com.gyeot.domain.safetycard.policy.DefaultSharingTypes;
import com.gyeot.domain.safetycard.repository.SafetyInformationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 요청 미리보기에서 공개할 항목을 고를 수 있도록 분류별 내 항목과 기본 선택값을 만듭니다. (F-03)
 * - requestId가 없으면(새 요청) 도움 종류의 기본 공개 유형에 속한 항목을 선택합니다.
 * - requestId가 있으면(요청 수정 H-03-01-02, 다시 부탁하기 N-06) 그 요청의 마지막 대상에게 공개했던 항목을 선택합니다.
 *   N-06은 이전 대상이 거절·마감한 뒤라 권한이 이미 끝났으므로, 공개 중(PENDING·ACTIVE)만으로는 찾을 수 없습니다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SharingOptionService {

    private final SafetyInformationRepository safetyInformationRepository;     // 내 안심카드 항목 조회
    private final RequestInformationPermissionRepository permissionRepository; // 요청에 공개했던 항목 조회

    // 정보 주인이 직접 뺀 항목은 다시 선택해 두지 않음 (삭제된 항목은 information_id가 NULL이라 쿼리에서 빠짐)
    private static final List<RevokeReason> EXCLUDED_REASONS =
            List.of(RevokeReason.SHARING_EXCLUDED, RevokeReason.OWNER_REVOKED, RevokeReason.ITEM_DELETED);

    public SharingOptionsResponse getSharingOptions(Long userId, HelpType helpType, Long requestId) {
        List<SafetyInformation> items = safetyInformationRepository.findAllByOwnerIdOrderByIdAsc(userId);
        Predicate<SafetyInformation> isSelected = selectionRule(helpType, requestId);

        // 분류 4개를 enum 순서대로 모두 내려줌 (항목이 없으면 빈 배열)
        List<CategoryOption> categories = Arrays.stream(InformationCategory.values())
                .map(category -> new CategoryOption(
                        category,
                        category.getDescription(),
                        items.stream()
                                .filter(item -> item.getInformationType().getCategory() == category)
                                .map(item -> toItemOption(item, isSelected.test(item)))
                                .toList()))
                .toList();

        return new SharingOptionsResponse(categories);
    }

    private Predicate<SafetyInformation> selectionRule(HelpType helpType, Long requestId) {
        if (requestId == null) {
            Set<InformationType> defaultTypes = DefaultSharingTypes.of(helpType);
            return item -> defaultTypes.contains(item.getInformationType());
        }

        // 공개했던 항목 ID와 내 항목 ID를 비교하므로, 남의 요청 ID를 넣어도 내 항목이 선택될 일이 없음
        Set<Long> sharingIds = new HashSet<>(permissionRepository.findLastSharedInformationIds(requestId, EXCLUDED_REASONS));
        return item -> sharingIds.contains(item.getId());
    }

    private ItemOption toItemOption(SafetyInformation item, boolean selected) {
        InformationType type = item.getInformationType();
        return new ItemOption(
                item.getId(),
                type,
                type.getDescription(),
                item.getTitle(),
                item.getInformationValue(),
                type.isSensitive(),
                selected,
                item.getUpdatedAt());
    }
}
