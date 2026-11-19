package com.gyeot.domain.situation.controller;

import com.gyeot.domain.situation.dto.response.HelpRecordPageResponse;
import com.gyeot.domain.situation.service.HelpRecordQueryService;
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
@RequestMapping("/api/help-records")
public class HelpRecordController {
    private final HelpRecordQueryService service;

    @GetMapping
    public ApiResponse<HelpRecordPageResponse> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long personId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.list(principal.userId(), type, personId, page, size));
    }
}
