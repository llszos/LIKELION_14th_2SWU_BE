package com.gyeot.domain.situation.service;

import com.gyeot.domain.relation.entity.RelationshipStatus;
import com.gyeot.domain.relation.repository.RelationshipQueryRepository;
import com.gyeot.domain.user.entity.AccountStatus;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import com.gyeot.global.security.auth.AuthErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;

/**
 * 생성 Service의 저장 전 검증용입니다. 팀 확인 전 임시 정책:
 * null·중복·본인 ID 거부, 생성자→대상 ACTIVE 관계 요구, 관계 미충족 FORBIDDEN.
 * 사용자 상태와 관계의 동시 변경을 잠그지는 않습니다. HTTP에는 아직 연결하지 않습니다.
 */
@Component
@RequiredArgsConstructor
public class SituationCreateParticipantValidator {
    private final UserRepository users;
    private final RelationshipQueryRepository relationships;

    public void validate(Long creatorId, List<Long> participantIds) {
        if (creatorId == null || participantIds == null)
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        var seen = new HashSet<Long>();
        // 요청 구조 전체를 먼저 검사하여 잘못된 목록은 A 조회 전에 거부합니다.
        for (Long id : participantIds) {
            if (id == null || creatorId.equals(id) || !seen.add(id))
                throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        for (Long id : participantIds) {
            if (!users.existsById(id)) throw new BusinessException(AuthErrorCode.USER_NOT_FOUND);
            if (users.existsByIdAndAccountStatus(id, AccountStatus.WITHDRAWN))
                throw new BusinessException(AuthErrorCode.USER_ALREADY_WITHDRAWN);
            if (!users.existsByIdAndAccountStatus(id, AccountStatus.ACTIVE))
                throw new BusinessException(AuthErrorCode.USER_NOT_ACTIVE);
            if (!relationships.existsBySourceUserIdAndTargetUserIdAndStatus(creatorId, id, RelationshipStatus.ACTIVE))
                throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }
}
