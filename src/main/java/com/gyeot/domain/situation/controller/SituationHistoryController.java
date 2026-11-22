package com.gyeot.domain.situation.controller;

import com.gyeot.domain.situation.dto.response.SituationHistoryListResponse;
import com.gyeot.domain.situation.dto.response.SituationHistoryDetailResponse;
import com.gyeot.domain.situation.service.SituationHistoryService;
import com.gyeot.domain.situation.service.SituationHistoryService.Filter;
import com.gyeot.domain.situation.dto.response.SituationHistoryPeopleResponse;
import com.gyeot.global.common.ApiResponse;
import com.gyeot.global.security.auth.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "ALL") Filter filter,
            @RequestParam(required = false) Long personId) {
        return ApiResponse.success(service.list(user.userId(), page, size, filter, personId));
    }

    @GetMapping("/history/people")
    public ApiResponse<SituationHistoryPeopleResponse> people(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "ALL") Filter filter) {
        return ApiResponse.success(service.people(user.userId(), filter));
    }
    @GetMapping("/{situationId}/history")
    public ApiResponse<SituationHistoryDetailResponse> detail(
            @PathVariable Long situationId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.detail(situationId, user.userId()));
    }
}
