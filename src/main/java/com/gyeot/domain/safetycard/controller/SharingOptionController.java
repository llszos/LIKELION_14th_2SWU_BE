package com.gyeot.domain.safetycard.controller;

import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.safetycard.dto.response.SharingOptionsResponse;
import com.gyeot.domain.safetycard.service.SharingOptionService;
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

/** 요청 작성·수정 때 공개할 안심카드 항목 선택지를 조회합니다. (F-03, 노션 D 파트 API 명세 '공개 항목 선택지 조회') */
@Tag(name = "안심카드", description = "내 안심카드 항목 조회, 작성, 수정, 삭제와 요청에 공개할 항목 선택지 API") // Swagger 화면의 묶음 이름 (두 컨트롤러가 같은 묶음)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/safety-information")
public class SharingOptionController {

    private final SharingOptionService sharingOptionService;

    /**
     * GET /api/safety-information/sharing-options?helpType=HOSPITAL&requestId=1
     * 로그인이 필요합니다. (토큰이 없으면 Security 설정에서 401)
     * helpType이 없거나 잘못된 값이면 공통 에러 400 INVALID_INPUT
     */
    @Operation(summary = "공개 항목 선택지 조회",
            description = "요청에 공개할 수 있는 내 항목을 분류별로, 도움 종류별 기본 선택값과 함께 조회합니다. "
                    + "요청 수정이나 다시 부탁하기는 requestId를 넣으면 마지막 대상에게 공개했던 항목이 선택됩니다.")
    @GetMapping("/sharing-options")
    public ApiResponse<SharingOptionsResponse> getSharingOptions(
            @AuthenticationPrincipal AuthenticatedUser user,       // 로그인한 사용자 (JWT 토큰에서 꺼냄, 요청 값으로 바꿀 수 없음)
            @RequestParam HelpType helpType,                       // 도움 종류 (새 요청의 기본 선택값 기준)
            @RequestParam(required = false) Long requestId) {     // 요청 수정·다시 부탁하기 때만 넣음
        return ApiResponse.success(sharingOptionService.getSharingOptions(user.userId(), helpType, requestId)); // 본인 항목만 조회
    }
}
