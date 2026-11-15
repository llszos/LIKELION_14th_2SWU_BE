package com.gyeot.domain.situation.controller;

import com.gyeot.domain.situation.dto.response.SituationParticipantsResponse;
import com.gyeot.domain.situation.service.SituationParticipantsQueryService;
import com.gyeot.global.common.ApiResponse;
import com.gyeot.global.security.auth.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 팀 확인 전 임시 참여자 목록 계약입니다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/situations")
public class SituationParticipantsController {
    private final SituationParticipantsQueryService service;

    @GetMapping("/{situationId}/participants")
    public ApiResponse<SituationParticipantsResponse> list(@PathVariable Long situationId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.list(situationId, user.userId()));
    }
}
