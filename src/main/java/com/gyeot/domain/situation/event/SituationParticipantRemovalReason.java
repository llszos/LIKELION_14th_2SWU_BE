package com.gyeot.domain.situation.event;

/**
 * C에 전달하는 참여 종료 원인입니다. DB 참여 상태(ACTIVE·LEFT·REMOVED)와 별개입니다.
 */
public enum SituationParticipantRemovalReason {
    /** 자진 이탈(LEFT) 또는 관계 해제 이외의 직접 제외(REMOVED). */
    PARTICIPANT_LEFT,

    /** 관계 해제로 인한 제외(REMOVED). */
    RELATIONSHIP_ENDED
}
