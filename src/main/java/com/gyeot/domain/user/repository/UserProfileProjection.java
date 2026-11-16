package com.gyeot.domain.user.repository;

import com.gyeot.domain.user.entity.AccountStatus;

/** 다른 영역에 제공할 최소 사용자 프로필 조회용 JPA projection입니다. */
public interface UserProfileProjection {

    Long getId();

    String getName();

    AccountStatus getAccountStatus();
}
