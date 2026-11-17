package com.gyeot.domain.access.controller;

import com.gyeot.domain.access.dto.response.SharedInformationResponse;
import com.gyeot.domain.access.service.SharedInformationService;
import com.gyeot.global.common.ApiResponse;
import com.gyeot.global.security.auth.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 담당자가 요청에서 공유받은 안심카드 정보를 조회합니다. (F-09, F-10, 노션 D 파트 API 명세 '전달받은 정보 조회')
 * URI는 요청 아래 경로(/api/care-requests)이지만, 처리는 D가 맡습니다. (API 공동 규칙의 URI 기준)
 */
@Tag(name = "전달받은 정보", description = "담당자가 요청에서 공유받은 안심카드 정보 조회 API") // Swagger 화면의 묶음 이름 (A 컨트롤러와 같은 형식)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/care-requests")
public class SharedInformationController {

    private final SharedInformationService sharedInformationService;

    /**
     * GET /api/care-requests/{requestId}/shared-information
     * 로그인이 필요합니다. (토큰이 없으면 Security 설정에서 401)
     *
     * - 볼 수 있으면 200 + accessible=true와 항목. 내려준 항목마다 정보 주인의 접근 이력(VIEWED)이 남으므로
     *   FE는 사용자가 '전달받은 정보'를 실제로 열었을 때만 호출합니다. (미리 불러오기 X)
     * - 이 요청의 담당자였지만 지금 볼 수 없으면 200 + accessible=false와 이유
     * - 이 요청과 무관한 사람은 404 SHARED_INFO_NOT_FOUND
     * - 급한 요청은 이번 범위(MUST)에서 지원하지 않아 404 SHARED_INFO_NOT_FOUND (SHOULD에서 추가)
     */
    @Operation(summary = "전달받은 정보 조회",
            description = "담당자가 요청에서 공유받은 안심카드 항목을 조회하고, 내려준 항목마다 정보 주인의 접근 이력을 남깁니다. "
                    + "이력이 남으므로 사용자가 화면을 실제로 열었을 때만 호출합니다. "
                    + "볼 수 없으면 accessible=false와 이유를 내려주고, 이 요청과 무관한 사람과 급한 요청은 404입니다.")
    @GetMapping("/{requestId}/shared-information")
    public ApiResponse<SharedInformationResponse> getSharedInformation(
            @AuthenticationPrincipal AuthenticatedUser user, // 로그인한 사용자 (JWT 토큰에서 꺼냄, 요청 값으로 바꿀 수 없음)
            @PathVariable Long requestId) {                   // 요청 ID
        return ApiResponse.success(sharedInformationService.getSharedInformation(requestId, user.userId()));
    }
}
