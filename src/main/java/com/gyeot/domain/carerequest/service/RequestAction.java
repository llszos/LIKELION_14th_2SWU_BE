package com.gyeot.domain.carerequest.service;

/**
 * 요청 상세에서 보는 사람이 지금 할 수 있는 동작입니다. (요청 상세 actions, 버튼 표시용)
 * 판단 기준은 각 API의 서비스 검증과 같게 맞췄습니다. 화면이 열린 사이 상태가 바뀌면 API가 409 등으로 다시 막습니다.
 */
public enum RequestAction {
    RESPOND,            // 대상자: 응답 (요청됨, 내 지정이 열려 있고 수락 전, 시작 전)
    WITHDRAW,           // 담당자: 수락 철회 (수락됨, 시작 전)
    START,              // 담당자: 돌봄 시작 (수락됨)
    COMPLETE,           // 담당자·요청자: 돌봄 완료 (진행 중)
    CANCEL,             // 요청자: 요청 취소 (끝나지 않은 요청)
    EDIT,               // 요청자: 요청 수정 (요청됨·수락됨, 시작 전)
    REASSIGN,           // 요청자: 다시 부탁하기 (담당자 없음, 시작 전)
    ACCEPT_PROPOSAL,    // 담당자: 수정 내용 확인 (내 지정의 재확인 대기, 기한 전)
    REJECT_PROPOSAL,    // 담당자: 수정 내용 거절 (ACCEPT_PROPOSAL과 같은 조건)
    WITHDRAW_PROPOSAL,  // 요청자: 수정 철회 (재확인 대기)
    DUPLICATE,          // 요청자: 같은 내용으로 새 요청 N-07 (완료·취소·만료)
    VIEW_PAST_SITUATION // 요청자·담당자: 지난 상황에서 보기 (요청 완료, 상황 종료)
}
