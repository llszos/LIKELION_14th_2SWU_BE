package com.gyeot.domain.relation.controller;

import com.gyeot.domain.relation.dto.request.InvitationCreateRequest;
import com.gyeot.domain.relation.dto.response.InvitationCreateResponse;
import com.gyeot.domain.relation.service.InvitationService;
import com.gyeot.global.common.ApiResponse;
import com.gyeot.global.security.auth.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 나의 사람 초대 링크 생성·취소 API입니다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/invitations")
@Tag(name = "초대", description = "나의 사람 초대 링크 생성·취소 API")
public class InvitationController {

    private final InvitationService invitationService;

    @PostMapping
    @Operation(summary = "나의 사람 초대 링크 생성", description = "7일 동안 유효한 MY_PERSON 초대 링크를 만듭니다.")
    public ResponseEntity<ApiResponse<InvitationCreateResponse>> createInvitation(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @Valid @RequestBody InvitationCreateRequest request
    ) {
        InvitationCreateResponse response = invitationService.createInvitation(
                authenticatedUser.userId(),
                request.invitationType()
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "초대 링크가 생성되었습니다."));
    }

    @DeleteMapping("/{invitationId}")
    @Operation(summary = "대기 중인 나의 사람 초대 취소", description = "발신자 본인의 대기 중인 초대 링크를 취소합니다.")
    public ResponseEntity<ApiResponse<Void>> cancelInvitation(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable Long invitationId
    ) {
        invitationService.cancelInvitation(authenticatedUser.userId(), invitationId);
        return ResponseEntity.ok(ApiResponse.success(null, "초대를 취소했습니다."));
    }
}