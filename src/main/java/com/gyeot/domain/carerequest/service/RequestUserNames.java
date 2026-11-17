package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.user.query.UserProfileQuery;
import com.gyeot.domain.user.query.UserProfileQuery.UserProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * C 응답에 보여 줄 사용자 이름을 한 번에 조회합니다. (A 사용자 기본 정보 일괄 조회 #57)
 * 담당자 없음 안내(#74), 대상 후보(#108), 요청 상세(#69)가 같은 규칙으로 이름을 채우도록 모았습니다.
 *
 * - 사용자 ID 목록을 한 번에 넘겨 조회합니다. (사람 수만큼 조회하지 않음)
 * - 탈퇴한 사용자는 "탈퇴한 사용자"로 표시합니다. (F-10, F-32, 팀 결정: 프론트에서 따로 처리하지 않도록 서버가 채움)
 * - A 결과에 없는 ID(드물지만 사용자 행이 없는 경우)도 빈칸이 되지 않도록 "탈퇴한 사용자"로 표시합니다.
 */
@Component
@RequiredArgsConstructor
public class RequestUserNames {
    public static final String WITHDRAWN_USER_NAME = "탈퇴한 사용자";

    private final UserProfileQuery userProfileQuery; // A: 사용자 이름, 탈퇴 여부

    /**
     * 사용자 ID → 화면에 보여 줄 이름
     * 넘긴 ID(null 제외)는 모두 결과에 들어 있습니다. 비어 있으면 조회하지 않습니다.
     */
    public Map<Long, String> namesOf(Collection<Long> userIds) {
        Map<Long, String> names = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return names;
        }
        for (UserProfile profile : userProfileQuery.findProfilesByUserIds(userIds).values())  {
            names.put(profile.userId(), profile.withdrawn() ? WITHDRAWN_USER_NAME : profile.name());
        }
        userIds.stream()
                .filter(Objects::nonNull)
                .forEach(userId -> names.putIfAbsent(userId, WITHDRAWN_USER_NAME)); // A 결과에 없는 ID
        return names;
    }
}