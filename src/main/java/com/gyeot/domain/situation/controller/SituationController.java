package com.gyeot.domain.situation.controller;

import com.gyeot.domain.situation.dto.response.SituationListResponse;
import com.gyeot.domain.situation.service.SituationQueryService;
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
public class SituationController {
    private final SituationQueryService service;

    @GetMapping
    public ApiResponse<SituationListResponse> list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.listActive(user.userId(), page, size));
    }
}
