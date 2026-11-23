package com.gyeot.domain.relation.controller;

import com.gyeot.domain.relation.dto.response.RelationshipEndResponse;
import com.gyeot.domain.relation.dto.response.RegisteredByOthersListResponse;
import com.gyeot.domain.relation.service.RegisteredByOthersListService;
import com.gyeot.domain.relation.service.RelationshipService;
import com.gyeot.global.common.ApiResponse;
import com.gyeot.global.security.auth.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 나의 사람·보호자 관계를 관리합니다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/relationships")
@Tag(name = "관계", description = "관계 목록 조회 및 관계 해제 API")
public class RelationshipController {

    private final RelationshipService relationshipService;
    private final RegisteredByOthersListService registeredByOthersListService;

    @GetMapping("/registered-by-others")
    @Operation(summary = "나를 나의 사람으로 등록한 사람 조회", description = "로그인 사용자를 대상으로 등록한 활성 MY_PERSON 관계를 최근 등록순으로 조회합니다.")
    public ResponseEntity<ApiResponse<RegisteredByOthersListResponse>> getRegisteredByOthers(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                registeredByOthersListService.getRegisteredByOthers(authenticatedUser.userId())));
    }

    @DeleteMapping("/{relationshipId}")
    @Operation(summary = "관계 해제", description = "나의 사람 관계는 요청 방향만, 보호자 관계는 양방향을 함께 종료합니다.")
    public ResponseEntity<ApiResponse<RelationshipEndResponse>> endRelationship(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable Long relationshipId
    ) {
        RelationshipEndResponse response = relationshipService.endRelationship(
                authenticatedUser.userId(), relationshipId);
        return ResponseEntity.ok(ApiResponse.success(response, "관계를 해제했습니다."));
    }
}
