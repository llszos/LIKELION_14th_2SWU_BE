package com.gyeot.domain.relation.controller;

import com.gyeot.domain.relation.dto.request.GuardianOrderChangeRequest;
import com.gyeot.domain.relation.service.RelationshipService;
import com.gyeot.global.common.ApiResponse;
import com.gyeot.global.security.auth.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 사용자 관계와 보호자 순서를 관리합니다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/relationships")
@Tag(name = "관계", description = "보호자 연락 순서 변경 API")
public class RelationshipController {

    private final RelationshipService relationshipService;

    @PatchMapping("/guardians/order")
    @Operation(
            summary = "보호자 연락 순서 변경",
            description = "로그인 사용자의 활성 보호자 전체 목록을 전달된 순서대로 재배치합니다. 나의 사람 순서에는 영향을 주지 않습니다."
    )
    public ResponseEntity<ApiResponse<Void>> changeGuardianOrder(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @Valid @RequestBody GuardianOrderChangeRequest request
    ) {
        relationshipService.changeGuardianOrder(authenticatedUser.userId(), request.targetUserIds());
        return ResponseEntity.ok(ApiResponse.success(null, "보호자 연락 순서를 변경했습니다."));
    }
}
