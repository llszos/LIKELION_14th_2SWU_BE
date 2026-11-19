package com.gyeot.domain.access.dto.response;

import com.gyeot.domain.safetycard.entity.InformationCategory;
import com.gyeot.domain.safetycard.entity.InformationType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 전달받은 정보 조회 응답입니다. (노션 D 파트 API 명세 '전달받은 정보 조회', H-03-01-01)
 *
 * - 볼 수 있으면 accessible=true, unavailableReason=null이고, categories에 공유받은 항목을 담습니다.
 *   공개 항목이 0개이거나 모두 빠졌으면 categories는 빈 배열입니다.
 * - 볼 수 없으면 accessible=false와 이유(unavailableReason)를 담고, categories는 빈 배열입니다. (항목 내용을 내려주지 않음)
 * - accessUntil은 접근 종료 화면에도 표시하므로 볼 수 없을 때도 내려줍니다.
 */
public record SharedInformationResponse(
        Long requestId,                       // 요청 ID
        boolean accessible,                   // 지금 볼 수 있는지
        LocalDateTime accessUntil,            // 접근 기한 (요청 종료 시각. 급한 요청은 이번 범위에서 404라 이 응답이 없음)
        UnavailableReason unavailableReason,  // 볼 수 없는 이유. 볼 수 있으면 null
        List<SharedCategory> categories       // 분류별 항목 (기본, 건강, 생활, 미리 남기는 말 순서, 항목이 없는 분류는 뺌)
) {

    public SharedInformationResponse {
        categories = List.copyOf(categories); // 만든 뒤 목록이 바뀌지 않게 복사
    }

    /** 볼 수 있을 때: 항목을 담아 내려줌 */
    public static SharedInformationResponse accessible(Long requestId, LocalDateTime accessUntil,
                                                       List<SharedCategory> categories) {
        return new SharedInformationResponse(requestId, true, accessUntil, null, categories);
    }

    /** 볼 수 없을 때: 이유만 내려주고 항목은 빈 배열 */
    public static SharedInformationResponse unavailable(Long requestId, LocalDateTime accessUntil,
                                                        UnavailableReason reason) {
        return new SharedInformationResponse(requestId, false, accessUntil, reason, List.of());
    }

    public record SharedCategory(
            InformationCategory category, // 분류
            String categoryName,          // 분류 이름 (화면의 분류 제목)
            List<SharedItem> items        // 항목 (작성순)
    ) {

        public SharedCategory {
            items = List.copyOf(items); // 만든 뒤 목록이 바뀌지 않게 복사
        }
    }

    public record SharedItem(
            Long informationId,              // 항목 ID
            InformationType informationType, // 항목 유형
            String typeName,                 // 항목 유형 이름
            String title,                    // 항목 이름. title이 없는 유형은 null
            String informationValue,         // 항목 내용
            LocalDateTime updatedAt,         // 최근 갱신 시각. 작성 후 수정하지 않았으면 작성 시각 (null 없음). 화면에는 updated=true일 때만 갱신일로 표시
            boolean updated,                 // 공유 이후 수정 여부. 공유된(ACTIVE가 된) 뒤에 정보 주인이 수정한 항목이면 true (피그마 코멘트 #91)
            boolean added,                   // '추가됨' 표시. 수락 이후 같은 지정에서 처음 공유된 항목 (다시 허용한 항목은 false)
            boolean sensitive                // '민감' 표시 (민감정보 5개)
    ) {
    }
}
