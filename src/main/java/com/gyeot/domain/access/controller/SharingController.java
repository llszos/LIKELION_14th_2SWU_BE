package com.gyeot.domain.access.controller;

import com.gyeot.domain.access.dto.response.ReceivedSharingResponse;
import com.gyeot.domain.access.dto.response.SharingListResponse;
import com.gyeot.domain.access.service.ReceivedSharingService;
import com.gyeot.domain.access.service.SharingListService;
import com.gyeot.global.common.ApiResponse;
import com.gyeot.global.security.auth.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공유 관리 API입니다. (F-30, 노션 D 파트 API 명세 '공유 중 목록 조회', '받은 정보 조회', '공유 직접 철회', '공유 다시 허용')
 * 지금은 공유 중 목록 조회와 받은 정보 조회가 열려 있고, 직접 철회와 다시 허용은 C 요청 잠금 계약(#124)이 들어온 뒤 이 컨트롤러에 추가합니다.
 */
@Tag(name = "공유 관리", description = "내 정보를 공유 중인 사람 확인, 직접 철회, 다시 허용, 받은 정보 조회 API") // Swagger 화면의 묶음 이름
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/sharings")
public class SharingController {

    private final SharingListService sharingListService;         // 공유 중 목록 (C-06, R-02-01)
    private final ReceivedSharingService receivedSharingService; // 받은 정보 (R-02-01)

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
}
