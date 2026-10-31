package com.gyeot.domain.user.controller;

import com.gyeot.domain.user.dto.request.LoginRequest;
import com.gyeot.domain.user.dto.request.SignupRequest;
import com.gyeot.domain.user.dto.response.LoginResponse;
import com.gyeot.domain.user.dto.response.SignupResponse;
import com.gyeot.domain.user.service.AuthService;
import com.gyeot.domain.user.service.LoginSession;
import com.gyeot.global.security.cookie.RefreshTokenCookieService;
import com.gyeot.global.security.refresh.service.IssuedRefreshToken;
import com.gyeot.global.security.refresh.service.RefreshTokenService;
import com.gyeot.global.security.jwt.JwtTokenProvider;
import com.gyeot.global.security.jwt.JwtProperties;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
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
    private final RefreshTokenService refreshTokenService;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenCookieService cookieService;
    private final JwtProperties jwtProperties;

    public AuthController(AuthService authService, RefreshTokenService refreshTokenService,
                          JwtTokenProvider jwtTokenProvider, RefreshTokenCookieService cookieService,
                          JwtProperties jwtProperties) {
        this.authService = authService;
        this.refreshTokenService = refreshTokenService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.cookieService = cookieService;
        this.jwtProperties = jwtProperties;
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
        LoginSession session = authService.login(request);
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        cookieService.addRefreshTokenCookie(headers, session.refreshToken());

        return ResponseEntity.ok().headers(headers).body(session.response());
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    @Operation(summary = "토큰 재발급", description = "Refresh Token 쿠키를 회전하고 새 Access Token을 발급합니다.")
    public ResponseEntity<LoginResponse> refresh(HttpServletRequest request) {
        IssuedRefreshToken rotated = refreshTokenService.rotate(cookieService.readRefreshToken(request));
        LoginResponse response = new LoginResponse(
                jwtTokenProvider.createAccessToken(rotated.userId()), "Bearer",
                jwtProperties.getAccessTokenTtl().toSeconds());
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        cookieService.addRefreshTokenCookie(headers, rotated);
        return ResponseEntity.ok().headers(headers).body(response);
    }

    @PostMapping("/logout")
    @SecurityRequirements
    @Operation(summary = "로그아웃", description = "현재 로그인 세션의 Refresh Token을 폐기합니다.")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        refreshTokenService.revokeCurrentSession(cookieService.readRefreshTokenIfPresent(request).orElse(null));
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        cookieService.clearRefreshTokenCookie(headers);
        return ResponseEntity.noContent().headers(headers).build();
    }
}
