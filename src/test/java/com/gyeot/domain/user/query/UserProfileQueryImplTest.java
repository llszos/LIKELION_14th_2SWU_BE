package com.gyeot.domain.user.query;

import com.gyeot.domain.user.entity.AccountStatus;
import com.gyeot.domain.user.repository.UserProfileProjection;
import com.gyeot.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** DB 없이 ID 일괄 전달과 활성·탈퇴 사용자 매핑을 확인합니다. */
class UserProfileQueryImplTest {

    private UserRepository userRepository;
    private UserProfileQuery query;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        query = new UserProfileQueryImpl(userRepository);
    }

    @Test
    void 활성_탈퇴_없는_사용자를_ID_목록으로_조회한다() {
        UserProfileProjection active = profile(1L, "김민주", AccountStatus.ACTIVE);
        UserProfileProjection withdrawn = profile(2L, null, AccountStatus.WITHDRAWN);
        when(userRepository.findProfilesByIds(List.of(1L, 2L, 99L))).thenReturn(List.of(active, withdrawn));

        List<UserProfileQuery.UserProfile> result = query.findProfilesByUserIds(
                Arrays.asList(1L, 2L, 99L, 1L, null));

        assertThat(result).containsExactly(
                new UserProfileQuery.UserProfile(1L, "김민주", false),
                new UserProfileQuery.UserProfile(2L, null, true));
        verify(userRepository).findProfilesByIds(List.of(1L, 2L, 99L));
    }

    @Test
    void 탈퇴_사용자의_이름은_응답에서_비식별화한다() {
        UserProfileProjection withdrawn = profile(2L, "탈퇴 전 이름", AccountStatus.WITHDRAWN);
        when(userRepository.findProfilesByIds(List.of(2L))).thenReturn(List.of(withdrawn));

        assertThat(query.findProfilesByUserIds(List.of(2L)))
                .containsExactly(new UserProfileQuery.UserProfile(2L, null, true));
    }

    @Test
    void 입력이_null이거나_비어_있으면_빈_목록을_반환하고_조회하지_않는다() {
        assertThat(query.findProfilesByUserIds(null)).isEmpty();
        assertThat(query.findProfilesByUserIds(List.of())).isEmpty();
        assertThat(query.findProfilesByUserIds(Arrays.asList(null, null))).isEmpty();

        verifyNoInteractions(userRepository);
    }

    private UserProfileProjection profile(Long id, String name, AccountStatus status) {
        UserProfileProjection projection = mock(UserProfileProjection.class);
        when(projection.getId()).thenReturn(id);
        when(projection.getName()).thenReturn(name);
        when(projection.getAccountStatus()).thenReturn(status);
        return projection;
    }
}
