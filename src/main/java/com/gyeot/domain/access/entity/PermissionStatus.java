package com.gyeot.domain.access.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 요청별 정보 접근 권한의 상태입니다.
 * (ERD 4.11 request_information_permissions 접근 권한 상태, ERD 7.2 주요 Enum 접근 권한)
 * DB의 status 컬럼에 이 이름(예: ACTIVE)이 문자열로 저장됩니다.
 * 상태는 PENDING → ACTIVE → REVOKED 순서로만 바뀌고, 되돌아가지 않습니다.
 * 다시 허용할 때는 기존 행을 되돌리지 않고 새 권한 행을 만듭니다.
 */

@Getter                     // 아래 필드 값을 꺼내는 getter 생성 (예: getDescription())
@RequiredArgsConstructor    // 아래 final 필드(description)를 채우는 생성자 자동 생성. PENDING("공유 예정")처럼 쓰려면 필요
public enum PermissionStatus {

    // 괄호 안 값: 한글 이름(description)
    PENDING("공유 예정"),   // 요청을 보냈고 담당자가 아직 수락하지 않음. 정보를 볼 수 없음.
    ACTIVE("접근 가능"),    // 담당자가 수락함. 조회 조건을 통과하면 정보를 볼 수 있음.
    REVOKED("접근 종료");   // 요청 종료·직접 철회 등으로 끝남. 다시 ACTIVE로 바뀌지 않음.

    private final String description; // 화면이나 문서에서 알아보기 쉬운 한글 이름 (한 번 정하면 바뀌지 않음)
}
