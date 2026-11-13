package com.gyeot.domain.safetycard.dto.response;

/**
 * 항목 삭제 응답입니다. (노션 D 파트 API 명세 '항목 삭제' 성공 응답)
 *
 * 삭제한 항목의 내용은 돌려주지 않고 ID만 돌려줍니다.
 * 삭제 후 FE는 '분류별 항목 조회'를 다시 불러 목록을 새로고침합니다.
 */
public record SafetyInformationDeleteResponse(
        Long informationId // 삭제한 항목 ID
) {
}
