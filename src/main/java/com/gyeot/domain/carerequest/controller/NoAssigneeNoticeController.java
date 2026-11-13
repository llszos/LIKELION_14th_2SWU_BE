package com.gyeot.domain.carerequest.controller;

import com.gyeot.domain.carerequest.dto.request.DismissNoticeRequest;
import com.gyeot.domain.carerequest.dto.response.NoAssigneeNoticesResponse;
import com.gyeot.domain.carerequest.service.NoAssigneeNoticeService;
import com.gyeot.global.common.ApiResponse;
import com.gyeot.global.security.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 담당자 없음 안내(모달 M-01) API입니다. (#74, F-14) */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/care-requests")
public class NoAssigneeNoticeController {
    private final NoAssigneeNoticeService noticeService;

    /** GET /api/care-requests/no-assignee-notices - 지금 띄울 모달 목록 (앱, 홈 진입 시) */
    @GetMapping("/no-assignee-notices")
    public ApiResponse<NoAssigneeNoticesResponse> getNotices(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(noticeService.getNotices(user.userId()));
    }

    /**
     * POST /api/care-requests/{requestId}/no-assignee-notice/dismiss - 모달을 닫음
     * 본문: {"reason": "UNAVAILABLE"} (목록에서 받은 reason 그대로). 성공하면 data는 null
     */
    @PostMapping("/{requestId}/no-assignee-notice/dismiss")
    public ApiResponse<Void> dismiss(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long requestId,
            @Valid @RequestBody DismissNoticeRequest request) {
        noticeService.dismiss(requestId, user.userId(), request.reason());
        return ApiResponse.success();
    }
}
