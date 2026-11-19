package com.gyeot.domain.access.dto.response;

/**
 * 담당자였던 사람이 전달받은 정보를 지금 볼 수 없는 이유입니다. (노션 D 파트 API 명세 '전달받은 정보 조회', F-09)
 *
 * - 응답의 unavailableReason으로 내려가고, FE는 이 값으로 접근 종료 화면의 문구를 고릅니다.
 * - FE는 문구를 4가지로 묶어 씁니다. (11/17 피그마 코멘트 #93 기획, 디자인 확정)
 *   완료(REQUEST_COMPLETED, REQUEST_ENDED_BY_TIME), 취소(REQUEST_CANCELED),
 *   철회 및 만료(ASSIGNMENT_CLOSED, OWNER_REVOKED, REQUEST_EXPIRED), 수락 전(NOT_ACCEPTED)
 * - DB에는 저장하지 않고, 조회할 때마다 요청 상태와 권한으로 판단합니다.
 */
public enum UnavailableReason {

    NOT_ACCEPTED,           // 아직 수락 전 (요청됨 상태이고 내가 지금 대상)
    REQUEST_COMPLETED,      // 요청 완료 (사용자가 완료했거나, 종료 시각이 지나 스케줄러가 완료로 바꿈)
    REQUEST_ENDED_BY_TIME,  // 종료 시각이 지났지만 스케줄러가 아직 완료로 바꾸기 전 (최대 1분)
    REQUEST_CANCELED,       // 요청 취소
    REQUEST_EXPIRED,        // 요청 만료 (시작 시각까지 담당자 없음)
    ASSIGNMENT_CLOSED,      // 내 지정이 끝남 (수락 철회, 대상 변경, 상황 나가기와 제외, 관계 해제)
    OWNER_REVOKED           // 정보 주인이 직접 철회
}
