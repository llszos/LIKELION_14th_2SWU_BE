package com.gyeot.domain.carerequest.query;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;

import java.time.LocalDateTime;

/**
 * 화면에 보여 줄 요청 상태입니다. DB에는 저장하지 않고 요청 상태·응답·기한으로 계산합니다. (ERD 7.4)
 * 요청됨(REQUESTED)을 F-14 정의에 따라 응답 대기 / 담당자 없음으로 나눕니다.
 * C 화면과 B 화면이 같은 기준을 쓰도록 계산은 여기서만 합니다.
 */
public enum RequestDisplayStatus {
    WAITING,      // 응답 대기: 요청됨 + 현재 대상이 미응답 또는 확인 후 답변 + 응답 기한 전
    NO_ASSIGNEE,  // 담당자 없음: 요청됨 + 어려움·기한 경과·지정 마감(철회·상황 나가기·관계 해제 등)
    ACCEPTED,     // 수락됨
    IN_PROGRESS,  // 진행 중
    COMPLETED,    // 완료
    CANCELED,     // 취소
    EXPIRED;      // 만료

    /**
     * @param latestAssignment 요청의 가장 최근 지정 (assignment_order가 가장 큰 행). 지정이 없으면 null
     */
    public static RequestDisplayStatus of(CareRequest request, RequestAssignment latestAssignment,
                                          LocalDateTime now) {
        return switch (request.getStatus()) {
            case ACCEPTED -> ACCEPTED;
            case IN_PROGRESS -> IN_PROGRESS;
            case COMPLETED -> COMPLETED;
            case CANCELED -> CANCELED;
            case EXPIRED -> EXPIRED;
            case REQUESTED -> isWaiting(latestAssignment, now) ? WAITING : NO_ASSIGNEE;
        };
    }

    private static boolean isWaiting(RequestAssignment assignment, LocalDateTime now) {
        if (assignment == null || !assignment.isOpen()) {
            return false;
        }
        if (assignment.getResponseStatus() == AssignmentResponseStatus.UNAVAILABLE) {
            return false;
        }
        return !assignment.isDeadlinePassed(now);
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELED || this == EXPIRED;
    }
}