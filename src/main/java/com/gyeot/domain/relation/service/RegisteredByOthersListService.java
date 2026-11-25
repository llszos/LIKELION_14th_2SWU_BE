package com.gyeot.domain.relation.service;

import com.gyeot.domain.relation.dto.response.RegisteredByOthersListResponse;
import com.gyeot.domain.relation.entity.RelationshipStatus;
import com.gyeot.domain.relation.entity.RelationshipType;
import com.gyeot.domain.relation.repository.RelationshipQueryRepository;
import com.gyeot.domain.user.query.UserProfileQuery;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** 로그인 사용자를 나의 사람으로 등록한 활성 관계를 조회합니다. */
@Service
@RequiredArgsConstructor
public class RegisteredByOthersListService {

    private final RelationshipQueryRepository relationshipQueryRepository;
    private final UserProfileQuery userProfileQuery;

    @Transactional(readOnly = true)
    public RegisteredByOthersListResponse getRegisteredByOthers(Long userId) {
        if (userId == null) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }

        var relationships = relationshipQueryRepository
                .findAllByTargetUserIdAndRelationshipTypeAndStatusOrderByCreatedAtDescIdDesc(
                        userId, RelationshipType.MY_PERSON, RelationshipStatus.ACTIVE);
        List<Long> sourceUserIds = relationships.stream().map(r -> r.getSourceUserId()).toList();
        Map<Long, UserProfileQuery.UserProfile> profiles = userProfileQuery.findProfilesByUserIds(sourceUserIds);

        List<RegisteredByOthersListResponse.Item> items = relationships.stream()
                .map(relationship -> {
                    UserProfileQuery.UserProfile profile = profiles.get(relationship.getSourceUserId());
                    return new RegisteredByOthersListResponse.Item(
                            relationship.getId(),
                            relationship.getSourceUserId(),
                            profile == null ? null : profile.name(),
                            relationship.getRelationshipType(),
                            relationship.getCreatedAt()
                    );
                })
                .toList();
        return new RegisteredByOthersListResponse(items);
    }
}
