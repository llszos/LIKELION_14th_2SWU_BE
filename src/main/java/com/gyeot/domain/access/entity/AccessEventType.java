package com.gyeot.domain.access.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 정보 접근 이력에 남기는 사건의 종류입니다.
 * (ERD 4.12 information_access_logs event_type, ERD 7.2 주요 Enum 접근 이벤트)
 * DB의 event_type 컬럼에 이 이름(예: VIEWED)이 문자열로 저장됩니다.
 * ACTIVE였던 권한에 대해서만 기록합니다. 수락 전(PENDING)에 끝난 권한은 볼 수 없었던 권한이라 기록하지 않습니다.
 */

@Getter                  // 아래 필드 값을 꺼내는 getter 생성 (예: getDescription())
@RequiredArgsConstructor // 아래 final 필드(description)를 채우는 생성자 자동 생성. GRANTED("부여")처럼 쓰려면 필요
public enum AccessEventType {

    // 괄호 안 값: 한글 이름(description)
    GRANTED("부여"),              // 권한이 ACTIVE가 됨 (담당자 수락, 요청 수정으로 항목 추가, 직접 철회 후 다시 허용)
    VIEWED("조회"),               // 담당자에게 안심카드 내용을 내려줌 (내려준 항목마다 1건)
    AUTO_REVOKED("자동 회수"),     // 직접 철회 외의 이유로 ACTIVE 권한이 끝남 (요청 종료, 대상 지정 마감, 항목 삭제, 공개 항목 제외, 기한 만료, 탈퇴)
    OWNER_REVOKED("직접 철회");    // 정보 주인이 공유 관리(C-06, R-02-01)에서 직접 철회함

    private final String description; // 화면이나 문서에서 알아보기 쉬운 한글 이름 (한 번 정하면 바뀌지 않음)
}