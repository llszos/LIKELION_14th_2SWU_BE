package com.gyeot.domain.relation.controller;

import com.gyeot.domain.relation.dto.request.EmergencyContactOrderChangeRequest;
import com.gyeot.domain.relation.dto.response.EmergencyContactListResponse;
import com.gyeot.domain.relation.dto.response.EmergencyContactOrderResponse;
import com.gyeot.domain.relation.service.EmergencyContactService;
import com.gyeot.global.common.ApiResponse;
import com.gyeot.global.security.auth.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 통합 비상연락망을 조회합니다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/emergency-contacts")
@Tag(name = "비상연락망", description = "비상연락망 조회 및 순서 변경 API")
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

    @PatchMapping("/order")
    @Operation(
            summary = "비상연락망 순서 변경",
            description = "활성 보호자와 추가 비상연락 대상의 전체 목록을 전달된 그룹별 순서로 함께 변경합니다."
    )
    public ResponseEntity<ApiResponse<EmergencyContactOrderResponse>> changeContactOrder(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @Valid @RequestBody EmergencyContactOrderChangeRequest request
    ) {
        EmergencyContactOrderResponse response = emergencyContactService.changeOrder(authenticatedUser.userId(), request);
        return ResponseEntity.ok(ApiResponse.success(response, "연락 순서를 변경했습니다."));
    }
}
