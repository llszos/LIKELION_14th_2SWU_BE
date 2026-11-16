package com.gyeot.domain.access.controller;

import com.gyeot.domain.access.dto.response.ReceivedSharingResponse;
import com.gyeot.domain.access.dto.response.SharingListResponse;
import com.gyeot.domain.access.dto.response.SharingReallowResponse;
import com.gyeot.domain.access.dto.response.SharingRevokeResponse;
import com.gyeot.domain.access.service.ReceivedSharingService;
import com.gyeot.domain.access.service.SharingListService;
import com.gyeot.domain.access.service.SharingReallowService;
import com.gyeot.domain.access.service.SharingRevokeService;
import com.gyeot.global.common.ApiResponse;
import com.gyeot.global.security.auth.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공유 관리 API입니다. (F-30, 노션 D 파트 API 명세 '공유 중 목록 조회', '받은 정보 조회', '공유 직접 철회', '공유 다시 허용')
 * - 조회 두 개(공유 중 목록, 받은 정보)는 읽기만 하고, 철회와 다시 허용은 권한을 바꿉니다.
 * - 철회와 다시 허용은 서비스가 C 요청 행을 먼저 잠근 쓰기 트랜잭션에서 처리합니다. (#124, 같은 요청의 C 변경과 차례로 실행)
 */
@Tag(name = "공유 관리", description = "내 정보를 공유 중인 사람 확인, 직접 철회, 다시 허용, 받은 정보 조회 API") // Swagger 화면의 묶음 이름
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/sharings")
public class SharingController {

    private final SharingListService sharingListService;         // 공유 중 목록 (C-06, R-02-01)
    private final ReceivedSharingService receivedSharingService; // 받은 정보 (R-02-01)
    private final SharingRevokeService sharingRevokeService;     // 직접 철회 (C-06, R-02-01)
    private final SharingReallowService sharingReallowService;   // 다시 허용 (C-06, R-02-01)

    /**
     * GET /api/sharings, GET /api/sharings?userId={userId}
     * 로그인이 필요합니다. (토큰이 없으면 Security 설정에서 401)
     * userId는 선택이고, 숫자가 아니면 공통 에러 400 INVALID_INPUT
     */
    @Operation(summary = "공유 중 목록 조회",
            description = "내 정보를 지금 볼 수 있는 사람을 (요청, 담당자) 카드 단위로 자동 회수 시각 빠른 순으로 조회합니다. "
                    + "userId를 넣으면 그 사람에게 공유한 카드만 조회합니다.")
    @GetMapping
    public ApiResponse<SharingListResponse> getSharings(
            @AuthenticationPrincipal AuthenticatedUser user,       // 로그인한 사용자 = 정보 주인 (JWT 토큰에서 꺼냄)
            @RequestParam(required = false) Long userId) {          // 받는 사람으로 거를 때만 (없으면 전체)
        return ApiResponse.success(sharingListService.getSharings(user.userId(), userId));
    }

    /**
     * GET /api/sharings/received?userId={userId}
     * 로그인이 필요합니다. (토큰이 없으면 Security 설정에서 401)
     * userId가 없거나 숫자가 아니면 공통 에러 400 INVALID_INPUT
     */
    @Operation(summary = "받은 정보 조회",
            description = "이 사람이 나에게 지금 공유 중인 정보를 요청 단위로, 항목 이름만 조회합니다. "
                    + "항목 내용을 내려주지 않아 정보 주인의 접근 이력에 남지 않습니다.")
    @GetMapping("/received")
    public ApiResponse<ReceivedSharingResponse> getReceived(
            @AuthenticationPrincipal AuthenticatedUser user, // 로그인한 사용자 = 받는 사람 (JWT 토큰에서 꺼냄)
            @RequestParam Long userId) {                      // 정보 주인 = 화면의 '이 사람'
        return ApiResponse.success(receivedSharingService.getReceived(user.userId(), userId));
    }

    /**
     * POST /api/sharings/{assignmentId}/revoke (본문 없음)
     * 로그인이 필요합니다. (토큰이 없으면 Security 설정에서 401)
     * - 카드(요청, 담당자)의 공개 항목을 모두 즉시 철회합니다. 이미 철회된 카드는 바꾸지 않고 같은 200 응답
     * - 카드가 없거나, 다른 사람의 공유거나, 요청이 끝났거나, 남은 항목이 없으면 404 SHARING_NOT_FOUND
     * - assignmentId가 숫자가 아니면 공통 에러 400 INVALID_INPUT
     */
    @Operation(summary = "공유 직접 철회",
            description = "공유 카드 하나(요청, 담당자)의 공개 항목을 모두 즉시 철회합니다. "
                    + "이미 철회된 카드는 변경 없이 200입니다.")
    @PostMapping("/{assignmentId}/revoke")
    public ApiResponse<SharingRevokeResponse> revoke(
            @AuthenticationPrincipal AuthenticatedUser user, // 로그인한 사용자 = 정보 주인 (JWT 토큰에서 꺼냄)
            @PathVariable Long assignmentId) {                // URL의 {assignmentId} = 공유 카드 ID
        return ApiResponse.success(sharingRevokeService.revoke(user.userId(), assignmentId), "공유를 철회했습니다.");
    }

    /**
     * POST /api/sharings/{assignmentId}/reallow (본문 없음)
     * 로그인이 필요합니다. (토큰이 없으면 Security 설정에서 401)
     * - 직접 철회했던 공유를 같은 요청 안에서 다시 허용합니다. 철회 뒤 삭제한 항목은 다시 열리지 않습니다.
     * - 카드가 없거나 다른 사람의 공유면 404 SHARING_NOT_FOUND
     * - 다시 열 수 없으면 409 SHARING_REALLOW_NOT_ALLOWED (이미 공유 중, 요청이 끝남, 담당자 변경, 관계 해제, 상황 나가기 등)
     * - 철회한 항목이 모두 삭제됐으면 409 SHARING_NOTHING_TO_REALLOW
     * - assignmentId가 숫자가 아니면 공통 에러 400 INVALID_INPUT
     */
    @Operation(summary = "공유 다시 허용",
            description = "직접 철회했던 공유를 같은 요청 안에서 다시 허용합니다. "
                    + "요청이 끝났거나 담당자가 바뀌었거나 관계, 상황 참여가 끝났으면 409입니다.")
    @PostMapping("/{assignmentId}/reallow")
    public ApiResponse<SharingReallowResponse> reallow(
            @AuthenticationPrincipal AuthenticatedUser user, // 로그인한 사용자 = 정보 주인 (JWT 토큰에서 꺼냄)
            @PathVariable Long assignmentId) {                // URL의 {assignmentId} = 공유 카드 ID
        return ApiResponse.success(sharingReallowService.reallow(user.userId(), assignmentId), "다시 허용했습니다.");
    }
}
