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

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            JwtProperties jwtProperties
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
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
            // TODO: 팀 공통 비즈니스 예외 체계에 연결합니다.
            throw new IllegalArgumentException("이미 가입된 이메일입니다.");
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
     * Refresh Token 발급 및 인증 오류의 공통 응답 처리는
     * 인증 2차에서 연결합니다.
     */
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        validatePasswordLength(request.password());

        User user = userRepository.findByEmail(request.email())
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

        return new LoginResponse(
                accessToken,
                "Bearer",
                jwtProperties.getAccessTokenTtl().toSeconds()
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

    private IllegalArgumentException loginFailed() {
        // TODO: 인증 2차에서 인증 예외 및 공통 응답 처리로 교체합니다.
        return new IllegalArgumentException(
                "이메일 또는 비밀번호가 올바르지 않습니다.");
    }
}
