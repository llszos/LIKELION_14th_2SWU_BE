package com.gyeot.domain.relation.controller;

import com.gyeot.domain.relation.dto.response.EmergencyContactListResponse;
import com.gyeot.domain.relation.service.EmergencyContactService;
import com.gyeot.global.common.ApiResponse;
import com.gyeot.global.security.auth.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 통합 비상연락망을 조회합니다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/relationships/emergency-contacts")
@Tag(name = "관계", description = "비상연락망 조회 API")
public class EmergencyContactController {

    private final EmergencyContactService emergencyContactService;

    @GetMapping
    @Operation(summary = "비상연락망 조회", description = "보호자와 추가 비상연락 대상을 전체 연락 순서로 조회합니다.")
    public ResponseEntity<ApiResponse<EmergencyContactListResponse>> getEmergencyContacts(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                emergencyContactService.getEmergencyContacts(authenticatedUser.userId())));
    }
}
