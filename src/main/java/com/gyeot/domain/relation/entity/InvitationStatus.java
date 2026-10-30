package com.gyeot.domain.relation.entity;

/** 초대 상태 */
public enum InvitationStatus {
    /** 수락·거절 대기 */
    PENDING,

    /** 수락 */
    ACCEPTED,

    /** 거절 */
    REJECTED,

    /** 만료 */
    EXPIRED,

    /** 취소 */
    CANCELED
}
