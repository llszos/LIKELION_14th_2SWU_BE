package com.gyeot.domain.situation.controller;

import com.gyeot.domain.situation.dto.response.SituationLeaveResponse;
import com.gyeot.domain.situation.service.SituationLeaveProcessor;
import com.gyeot.global.common.ApiResponse;
import com.gyeot.global.security.auth.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/situations")
public class SituationLeaveController {
    private final SituationLeaveProcessor processor;

    @PostMapping("/{situationId}/leave")
    public ApiResponse<SituationLeaveResponse> leave(
            @PathVariable Long situationId, @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(processor.leave(situationId, user.userId()));
    }
}
