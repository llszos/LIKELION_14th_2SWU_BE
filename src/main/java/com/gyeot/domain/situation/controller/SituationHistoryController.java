package com.gyeot.domain.situation.controller;

import com.gyeot.domain.situation.dto.response.SituationHistoryListResponse;
import com.gyeot.domain.situation.service.SituationHistoryService;
import com.gyeot.global.common.ApiResponse;
import com.gyeot.global.security.auth.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/situations")
public class SituationHistoryController {
    private final SituationHistoryService service;

    @GetMapping("/history")
    public ApiResponse<SituationHistoryListResponse> list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.list(user.userId(), page, size));
    }
}
