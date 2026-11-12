package com.gyeot.domain.carerequest.service;

/**
 * 요청을 보는 사람의 역할입니다. (요청 상세 viewerRole)
 * 역할에 따라 응답 필드와 할 수 있는 동작이 달라집니다.
 */
public enum RequestViewerRole {
    REQUESTER, // 요청자 (정보 주인)
    TARGET,    // 대상자 (요청을 받았고 수락하지 않음, 과거 대상 포함)
    ASSIGNEE   // 담당자 (요청을 받아 수락함)
}