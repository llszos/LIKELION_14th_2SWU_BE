package com.gyeot.domain.relation.repository;

/** 관계 잠금 전에 사용자 쌍을 확인하기 위한 최소 조회 결과입니다. */
public record RelationshipParticipants(Long sourceUserId, Long targetUserId) {
}
