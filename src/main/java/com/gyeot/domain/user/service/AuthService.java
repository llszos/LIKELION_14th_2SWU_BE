package com.gyeot.domain.user.service;

import com.gyeot.domain.user.dto.request.LoginRequest;
import com.gyeot.domain.user.dto.request.SignupRequest;
import com.gyeot.domain.user.dto.response.LoginResponse;
import com.gyeot.domain.user.dto.response.SignupResponse;
import com.gyeot.domain.user.entity.AccountStatus;
import com.gyeot.domain.user.entity.User;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.security.jwt.JwtProperties;
import com.gyeot.global.security.jwt.JwtTokenProvider;
import com.gyeot.global.security.auth.AuthErrorCode;
import com.gyeot.global.security.auth.AuthException;
import com.gyeot.global.security.refresh.service.IssuedRefreshToken;
import com.gyeot.global.security.refresh.service.RefreshTokenService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

/**
 * 회원가입·로그인 처리를 담당합니다.
 */
@Service
public class AuthService {

    private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final RefreshTokenService refreshTokenService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            JwtProperties jwtProperties,
            RefreshTokenService refreshTokenService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
        this.refreshTokenService = refreshTokenService;
    }

    /**
     * 이메일 중복을 확인하고 비밀번호를 해시하여 사용자를 등록합니다.
     *
     * 요청 필드의 기본 검증은 컨트롤러의 @Valid로 수행합니다.
     */
    @Transactional
    public SignupResponse signup(SignupRequest request) {
        validatePasswordLength(request.password());

        if (userRepository.existsByEmail(request.email())) {
            throw new AuthException(AuthErrorCode.EMAIL_ALREADY_REGISTERED);
        }

        String passwordHash =
                passwordEncoder.encode(request.password());

        User user = User.register(
                request.email(),
                passwordHash,
                request.name()
        );

        User savedUser = userRepository.save(user);

        return new SignupResponse(savedUser.getId());
    }

    /**
     * 이메일·비밀번호와 계정 상태를 확인하고 Access Token을 발급합니다.
     *
     * Refresh Token도 함께 발급하고 원문은 쿠키로 전달합니다.
     */
    @Transactional
    public LoginSession login(LoginRequest request) {
        validatePasswordLength(request.password());

        User user = userRepository.findByEmailForUpdate(request.email())
                .orElseThrow(this::loginFailed);

        if (user.getAccountStatus() != AccountStatus.ACTIVE
                || user.getPasswordHash() == null
                || user.getPasswordHash().isBlank()) {
            throw loginFailed();
        }

        if (!passwordEncoder.matches(
                request.password(), user.getPasswordHash())) {
            throw loginFailed();
        }

        String accessToken =
                jwtTokenProvider.createAccessToken(user.getId());
        IssuedRefreshToken refreshToken = refreshTokenService.issueInitial(user.getId());

        return new LoginSession(
                new LoginResponse(accessToken, "Bearer", jwtProperties.getAccessTokenTtl().toSeconds()),
                refreshToken
        );
    }

    private void validatePasswordLength(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("비밀번호는 필수입니다.");
        }

        if (password.getBytes(StandardCharsets.UTF_8).length
                > BCRYPT_MAX_PASSWORD_BYTES) {
            throw new IllegalArgumentException(
                    "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.");
        }
    }

    private AuthException loginFailed() {
        return new AuthException(AuthErrorCode.LOGIN_FAILED);
    }
}
