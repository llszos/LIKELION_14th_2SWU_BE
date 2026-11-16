package com.gyeot.domain.relation.dto.response;

import com.gyeot.domain.relation.entity.RelationshipType;

import java.util.List;

/** 보호자와 추가 비상연락 대상을 합친 연락망 조회 결과입니다. */
public record EmergencyContactListResponse(List<Item> items) {

    public record Item(
            Long userId,
            String name,
            RelationshipType relationshipType,
            String personalLabel,
            Integer priority,
            Integer guardianPriority
    ) {
    }
}
