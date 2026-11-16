package com.gyeot.domain.home.controller;

import com.gyeot.domain.home.dto.response.HomeResponse;
import com.gyeot.domain.home.service.HomeService;
import com.gyeot.global.common.ApiResponse;
import com.gyeot.global.security.auth.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/home")
public class HomeController {
    private final HomeService service;

    @GetMapping
    public ApiResponse<HomeResponse> getHome(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.getHome(user.userId()));
    }
}
