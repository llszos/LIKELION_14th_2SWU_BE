package com.gyeot.domain.relation.dto.response;

import com.gyeot.domain.relation.entity.RelationshipType;

import java.time.LocalDateTime;
import java.util.List;

/** 로그인 사용자를 나의 사람으로 등록한 활성 관계 목록입니다. */
public record RegisteredByOthersListResponse(List<Item> items) {

    public record Item(
            Long relationshipId,
            Long userId,
            String name,
            RelationshipType relationshipType,
            LocalDateTime createdAt
    ) {
    }
}
