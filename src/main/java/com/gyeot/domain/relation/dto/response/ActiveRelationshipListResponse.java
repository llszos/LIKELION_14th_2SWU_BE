package com.gyeot.domain.relation.dto.response;

import com.gyeot.domain.relation.entity.RelationshipType;

import java.util.List;

/** 로그인 사용자가 등록한 활성 관계 목록입니다. */
public record ActiveRelationshipListResponse(List<Item> items) {

    public record Item(
            Long relationshipId,
            Long userId,
            String name,
            RelationshipType relationshipType,
            String personalLabel,
            Integer priority,
            Integer guardianPriority
    ) {
    }
}
