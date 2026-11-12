package com.gyeot.domain.situation.controller;

import com.gyeot.domain.situation.dto.response.SituationEndResponse;
import com.gyeot.domain.situation.service.SituationEndService;
import com.gyeot.global.common.ApiResponse;
import com.gyeot.global.security.auth.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/situations")
public class SituationEndController {
    private final SituationEndService service;

    @PostMapping("/{situationId}/end")
    @Operation(summary = "상황 직접 종료")
    public ApiResponse<SituationEndResponse> end(
            @PathVariable Long situationId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.end(situationId, user.userId()));
    }
}
