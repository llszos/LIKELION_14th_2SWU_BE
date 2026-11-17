package com.gyeot.domain.relation.dto.response;

import com.gyeot.domain.relation.entity.RelationshipType;

import java.util.List;

/** 순서 변경 후의 통합 연락 순서입니다. */
public record EmergencyContactOrderResponse(List<Item> items) {

    public record Item(
            Long contactUserId,
            RelationshipType relationshipType,
            Integer priority
    ) {
    }
}
