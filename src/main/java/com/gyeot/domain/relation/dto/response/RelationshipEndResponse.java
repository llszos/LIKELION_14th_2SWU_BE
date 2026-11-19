package com.gyeot.domain.relation.dto.response;

import com.gyeot.domain.relation.entity.RelationshipType;

import java.time.LocalDateTime;

/** 관계 해제 결과입니다. */
public record RelationshipEndResponse(
        Long relationshipId,
        RelationshipType relationshipType,
        LocalDateTime endedAt
) {
}
