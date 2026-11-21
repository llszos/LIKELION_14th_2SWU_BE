package com.gyeot.domain.situation.controller;

import com.gyeot.domain.situation.dto.request.SituationParticipantAddRequest;
import com.gyeot.domain.situation.dto.response.SituationParticipantAddResponse;
import com.gyeot.domain.situation.service.SituationParticipantAddService;
import com.gyeot.global.common.ApiResponse;
import com.gyeot.global.security.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/situations")
public class SituationParticipantAddController {
    private final SituationParticipantAddService service;
    @PostMapping("/{situationId}/participants")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SituationParticipantAddResponse> add(@PathVariable Long situationId,
            @AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody SituationParticipantAddRequest request) {
        return ApiResponse.success(service.add(situationId, user.userId(), request.userId()));
    }
}
