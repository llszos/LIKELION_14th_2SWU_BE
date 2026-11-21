package com.gyeot.domain.situation.controller;

import com.gyeot.domain.situation.dto.internal.SituationParticipantRemovalResult;
import com.gyeot.domain.situation.service.SituationParticipantRemovalService;
import com.gyeot.global.common.ApiResponse;
import com.gyeot.global.security.auth.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/situations")
public class SituationParticipantRemovalController {
    private final SituationParticipantRemovalService service;

    @DeleteMapping("/{situationId}/participants/{participantId}")
    public ApiResponse<SituationParticipantRemovalResult> remove(@PathVariable Long situationId,
            @PathVariable Long participantId, @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.remove(situationId, participantId, user.userId()));
    }
}
