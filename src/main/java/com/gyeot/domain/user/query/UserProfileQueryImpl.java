package com.gyeot.domain.user.query;

import com.gyeot.domain.user.entity.AccountStatus;
import com.gyeot.domain.user.repository.UserProfileProjection;
import com.gyeot.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/** 사용자 엔티티를 외부에 노출하지 않고 최소 프로필만 일괄 조회합니다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserProfileQueryImpl implements UserProfileQuery {

    private final UserRepository userRepository;

    @Override
    public List<UserProfile> findProfilesByUserIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }

        List<Long> distinctUserIds = userIds.stream()
                .filter(userId -> userId != null)
                .distinct()
                .toList();
        if (distinctUserIds.isEmpty()) {
            return List.of();
        }

        return userRepository.findProfilesByIds(distinctUserIds).stream()
                .map(this::toUserProfile)
                .toList();
    }

    private UserProfile toUserProfile(UserProfileProjection profile) {
        boolean withdrawn = profile.getAccountStatus() == AccountStatus.WITHDRAWN;
        return new UserProfile(
                profile.getId(),
                withdrawn ? null : profile.getName(),
                withdrawn
        );
    }
}
