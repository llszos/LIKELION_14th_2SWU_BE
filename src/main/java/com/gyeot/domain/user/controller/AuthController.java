package com.gyeot.domain.user.controller;

import com.gyeot.domain.user.dto.request.LoginRequest;
import com.gyeot.domain.user.dto.request.SignupRequest;
import com.gyeot.domain.user.dto.response.LoginResponse;
import com.gyeot.domain.user.dto.response.SignupResponse;
import com.gyeot.domain.user.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 이메일·비밀번호 기반 회원가입·로그인 API입니다.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "인증", description = "회원가입·로그인 API")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/signup")
    @SecurityRequirements
    @Operation(
            summary = "회원가입",
            description = "이메일·비밀번호·이름으로 가입합니다. 가입 후 자동 로그인은 하지 않습니다."
    )
    public ResponseEntity<SignupResponse> signup(
            @Valid @RequestBody SignupRequest request
    ) {
        SignupResponse response = authService.signup(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(
            summary = "로그인",
            description = "이메일·비밀번호를 확인하고 Access Token을 발급합니다."
    )
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request
    ) {
        LoginResponse response = authService.login(request);

        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(response);
    }
}
