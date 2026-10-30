package com.gyeot.domain.access.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 접근 권한이 끝난(REVOKED) 이유입니다.
 * (ERD 4.11 request_information_permissions revoke_reason, ERD 7.2 주요 Enum 권한 철회 사유)
 * DB의 revoke_reason 컬럼에 이 이름(예: OWNER_REVOKED)이 문자열로 저장됩니다.
 * 권한이 끝나지 않은 상태(PENDING, ACTIVE)에서는 비어 있습니다(NULL).
 * 자세한 원인(완료인지 취소인지 등)은 요청, 대상 지정 상태에 있으므로, 여기에는 큰 분류만 저장합니다.
 */

@Getter                  // 아래 필드 값을 꺼내는 getter 생성 (예: getDescription())
@RequiredArgsConstructor // 아래 final 필드(description)를 채우는 생성자 자동 생성. REQUEST_ENDED("요청 종료")처럼 쓰려면 필요
public enum RevokeReason {

    // 괄호 안 값: 한글 이름(description)
    REQUEST_ENDED("요청 종료"),           // 요청 완료, 종료 시간 도달, 취소, 만료, 재확인 거절, 상황 종료
    ASSIGNMENT_CLOSED("대상 지정 마감"),   // 담당자 수락 철회, 대상 변경(다시 부탁하기), 상황 나가기, 제외, 관계 해제
    SHARING_EXCLUDED("공개 항목 제외"),    // 요청 수정으로 공개 항목에서 제외됨
    ITEM_DELETED("항목 삭제"),            // 정보 주인이 안심카드 항목을 삭제함
    OWNER_REVOKED("직접 철회"),           // 정보 주인이 공유 관리(C-06, R-02-01)에서 직접 철회함
    ACCESS_EXPIRED("접근 기한 만료"),      // 급한 요청 수락 후 24시간이 지남
    USER_WITHDRAWN("회원 탈퇴");          // 정보 주인 또는 담당자가 탈퇴함

    private final String description; // 화면이나 문서에서 알아보기 쉬운 한글 이름 (한 번 정하면 바뀌지 않음)
}