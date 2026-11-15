package com.gyeot.domain.relation.controller;

import com.gyeot.domain.relation.dto.request.InvitationCreateRequest;
import com.gyeot.domain.relation.dto.response.InvitationCreateResponse;
import com.gyeot.domain.relation.dto.response.InvitationDetailsResponse;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 나의 사람 초대와 보호자 요청 링크 조회·생성·응답·취소 API입니다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/invitations")
@Tag(name = "초대", description = "나의 사람 초대와 보호자 요청 링크 조회·생성·응답·취소 API")
public class InvitationController {

    private final InvitationService invitationService;

    @GetMapping("/{token}")
    @Operation(summary = "초대 링크 조회", description = "링크의 초대 유형, 발신자, 상태, 만료 여부와 보호자 인원 제한을 조회합니다.")
    public ApiResponse<InvitationDetailsResponse> getInvitation(
            @PathVariable String token,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser
    ) {
        Long viewerId = authenticatedUser == null ? null : authenticatedUser.userId();
        return ApiResponse.success(invitationService.getInvitation(token, viewerId));
    }

    @PostMapping
    @Operation(summary = "초대 링크 생성", description = "MY_PERSON 또는 GUARDIAN 유형의 7일 유효 링크를 만듭니다.")
    public ResponseEntity<ApiResponse<InvitationCreateResponse>> createInvitation(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @Valid @RequestBody InvitationCreateRequest request
    ) {
        InvitationCreateResponse response = invitationService.createInvitation(
                authenticatedUser.userId(),
                request.invitationType(),
                request.personalLabel(),
                request.message(),
                request.promiseConfirmed()
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

    @PostMapping("/{token}/accept")
    @Operation(summary = "초대 수락", description = "초대 유형별 응답을 기록합니다. 보호자 관계 생성·전환은 후속 관계 처리 범위에서 수행합니다.")
    public ResponseEntity<ApiResponse<Void>> acceptInvitation(
            @PathVariable String token,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser
    ) {
        invitationService.acceptInvitation(token, authenticatedUser.userId());
        return ResponseEntity.ok(ApiResponse.success(null, "초대를 수락했습니다."));
    }

    @PostMapping("/{token}/reject")
    @Operation(summary = "초대 거절", description = "로그인 사용자를 응답자로 기록하고 초대를 거절합니다.")
    public ResponseEntity<ApiResponse<Void>> rejectInvitation(
            @PathVariable String token,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser
    ) {
        invitationService.rejectInvitation(token, authenticatedUser.userId());
        return ResponseEntity.ok(ApiResponse.success(null, "초대를 거절했습니다."));
    }

    @PostMapping("/{token}/reciprocal")
    @Operation(summary = "반대 방향 나의 사람 추가", description = "초대 수락자가 수락한 초대 발신자를 나의 사람으로 한 번에 추가합니다.")
    public ResponseEntity<ApiResponse<Void>> addReciprocalRelationship(
            @PathVariable String token,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser
    ) {
        invitationService.addReciprocalRelationship(token, authenticatedUser.userId());
        return ResponseEntity.ok(ApiResponse.success(null, "나의 사람으로 추가했습니다."));
    }
}
