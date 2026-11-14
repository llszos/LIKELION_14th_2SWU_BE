package com.gyeot.domain.user.service;

import com.gyeot.domain.user.dto.request.LoginRequest;
import com.gyeot.domain.user.dto.request.SignupRequest;
import com.gyeot.domain.user.dto.response.LoginResponse;
import com.gyeot.domain.user.dto.response.SignupResponse;
import com.gyeot.domain.user.entity.AccountStatus;
import com.gyeot.domain.user.entity.User;
import com.gyeot.domain.user.entity.UserConsent;
import com.gyeot.domain.user.entity.UserConsentType;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.domain.user.repository.UserConsentRepository;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
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
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 회원가입·로그인 처리를 담당합니다.
 */
@Service
public class AuthService {

    private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;
    // 새 개인정보 제공 동의 문서가 확정되면 이 버전을 올립니다.
    private static final String PRIVACY_POLICY_VERSION = "v1";

    private final UserRepository userRepository;
    private final UserConsentRepository userConsentRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final RefreshTokenService refreshTokenService;
    private final Clock clock;

    public AuthService(
            UserRepository userRepository,
            UserConsentRepository userConsentRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            JwtProperties jwtProperties,
            RefreshTokenService refreshTokenService,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.userConsentRepository = userConsentRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
        this.refreshTokenService = refreshTokenService;
        this.clock = clock;
    }

    /**
     * 이메일 중복을 확인하고 비밀번호를 해시하여 사용자를 등록합니다.
     *
     * 요청 필드의 기본 검증은 컨트롤러의 @Valid로 수행합니다.
     */
    @Transactional
    public SignupResponse signup(SignupRequest request) {
        validatePasswordLength(request.password());
        if (!Boolean.TRUE.equals(request.privacyConsent())) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }

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
        userConsentRepository.save(UserConsent.record(
                savedUser.getId(),
                UserConsentType.PRIVACY_POLICY,
                PRIVACY_POLICY_VERSION,
                LocalDateTime.now(clock)
        ));

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
            throw new AuthException(AuthErrorCode.PASSWORD_REQUIRED);
        }

        if (password.getBytes(StandardCharsets.UTF_8).length
                > BCRYPT_MAX_PASSWORD_BYTES) {
            throw new AuthException(AuthErrorCode.PASSWORD_TOO_LONG);
        }
    }

    private AuthException loginFailed() {
        return new AuthException(AuthErrorCode.LOGIN_FAILED);
    }
}
