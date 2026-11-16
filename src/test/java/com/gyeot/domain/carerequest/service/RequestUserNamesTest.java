package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.user.query.UserProfileQuery;
import com.gyeot.domain.user.query.UserProfileQuery.UserProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 이름 표시 규칙만 확인합니다. (A 조회는 목) */
class RequestUserNamesTest {

    private UserProfileQuery userProfileQuery;
    private RequestUserNames userNames;

    @BeforeEach
    void setUp() {
        userProfileQuery = mock(UserProfileQuery.class);
        userNames = new RequestUserNames(userProfileQuery);
    }

    @Test
    void 활성_사용자는_이름_탈퇴했거나_A_결과에_없는_사용자는_탈퇴한_사용자다() {
        when(userProfileQuery.findProfilesByUserIds(any())).thenReturn(Map.of(
                1L, new UserProfile(1L, "박민재", false),
                2L, new UserProfile(2L, null, true)));

        assertThat(userNames.namesOf(Arrays.asList(1L, 2L, 3L, null)))
                .containsEntry(1L, "박민재")
                .containsEntry(2L, RequestUserNames.WITHDRAWN_USER_NAME)
                .containsEntry(3L, RequestUserNames.WITHDRAWN_USER_NAME) // A 결과에 없는 ID
                .hasSize(3);                                              // null은 빠짐
    }

    @Test
    void 비어_있으면_A를_조회하지_않는다() {
        assertThat(userNames.namesOf(List.of())).isEmpty();
        assertThat(userNames.namesOf(null)).isEmpty();

        verifyNoInteractions(userProfileQuery);
    }
}