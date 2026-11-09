package com.gyeot.domain.safetycard.controller;

import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.safetycard.dto.response.SharingOptionsResponse;
import com.gyeot.domain.safetycard.service.SharingOptionService;
import com.gyeot.global.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 요청 작성·수정 때 공개할 안심카드 항목 선택지를 조회합니다. (F-03, 노션 D 명세 '공개 항목 선택지 조회') */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/safety-information")
public class SharingOptionController {

    private final SharingOptionService sharingOptionService;

    /**
     * GET /api/safety-information/sharing-options?helpType=HOSPITAL&requestId=1
     * helpType이 없거나 잘못된 값이면 공통 에러 400 INVALID_INPUT
     */
    @GetMapping("/sharing-options")
    public ApiResponse<SharingOptionsResponse> getSharingOptions(
            // TODO: #33(JWT 인증) 병합 후 @AuthenticationPrincipal AuthenticatedUser로 교체. 그전까지 임시 사용자 ID 헤더 사용
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam HelpType helpType,                       // 도움 종류 (새 요청의 기본 선택값 기준)
            @RequestParam(required = false) Long requestId) {     // 요청 수정·다시 부탁하기 때만 넣음
        return ApiResponse.success(sharingOptionService.getSharingOptions(userId, helpType, requestId));
    }
}
