package com.gyeot.domain.user.query;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** 다른 영역에서 사용자 최소 프로필을 ID 목록으로 한 번에 조회하기 위한 계약입니다. */
public interface UserProfileQuery {

    /**
     * 사용자 ID 목록에 해당하는 사용자 프로필을 한 번에 조회합니다.
     * 탈퇴 사용자는 withdrawn이 true이고 이름은 null입니다.
     * 존재하지 않는 ID는 결과에서 제외하며, 반환 순서는 보장하지 않습니다.
     * 입력이 null 또는 비어 있으면 빈 목록을 반환합니다.
     *
     * @param userIds 조회할 사용자 ID 목록
     * @return 존재하는 사용자들의 최소 프로필 목록
     */
    List<UserProfile> findProfilesByUserIds(Collection<Long> userIds);

    /** 다른 영역에 필요한 사용자 ID, 표시 이름, 탈퇴 여부만 담습니다. */
    record UserProfile(Long userId, String name, boolean withdrawn) {
        public UserProfile {
            Objects.requireNonNull(userId, "userId는 필수입니다.");
            if (withdrawn && name != null) {
                throw new IllegalArgumentException("탈퇴 사용자의 이름은 비식별화되어야 합니다.");
            }
        }
    }
}
