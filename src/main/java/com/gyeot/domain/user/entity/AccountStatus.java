package com.gyeot.domain.user.entity;

/** 사용자의 계정 상태입니다. */
public enum AccountStatus {
    /** 가입 후 서비스를 이용할 수 있는 상태 */
    ACTIVE,

    /** 탈퇴 처리되어 로그인과 신규 서비스 이용이 차단된 상태 */
    WITHDRAWN
}
