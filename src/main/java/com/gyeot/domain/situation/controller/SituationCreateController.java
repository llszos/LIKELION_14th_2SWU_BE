package com.gyeot.domain.situation.controller;

import com.gyeot.domain.situation.service.SituationCreateService;
import com.gyeot.domain.situation.dto.request.SituationCreateRequest;
import com.gyeot.domain.situation.dto.response.SituationCreateResponse;
import com.gyeot.global.common.ApiResponse;
import com.gyeot.global.security.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/situations")
public class SituationCreateController {
    private final SituationCreateService service;
    /** 종류 계약 준비 전에는 503을 반환하며 저장하지 않습니다. */
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SituationCreateResponse> create(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody SituationCreateRequest request) {
        return ApiResponse.success(service.create(user.userId(), request));
    }
}
