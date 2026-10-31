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
import com.gyeot.global.security.auth.AuthException;
import com.gyeot.global.security.refresh.service.IssuedRefreshToken;
import com.gyeot.global.security.refresh.service.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private org.springframework.security.crypto.password.PasswordEncoder
            passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private RefreshTokenService refreshTokenService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setAccessTokenTtl(Duration.ofMinutes(15));

        authService = new AuthService(
                userRepository,
                passwordEncoder,
                jwtTokenProvider,
                properties,
                refreshTokenService
        );
    }

    @Test
    void signupStoresEncodedPasswordAndReturnsUserId() {
        SignupRequest request =
                new SignupRequest("user@example.com", "password", "사용자");

        when(userRepository.existsByEmail(request.email())).thenReturn(false);
        when(passwordEncoder.encode(request.password()))
                .thenReturn("encoded-password");

        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);

            assertEquals(request.email(), user.getEmail());
            assertEquals(request.name(), user.getName());
            assertEquals("encoded-password", user.getPasswordHash());
            assertEquals(AccountStatus.ACTIVE, user.getAccountStatus());

            User savedUser = mock(User.class);
            when(savedUser.getId()).thenReturn(17L);
            return savedUser;
        });

        SignupResponse response = authService.signup(request);

        assertEquals(17L, response.userId());
        verify(passwordEncoder).encode(request.password());
        verify(userRepository).save(any(User.class));
    }

    @Test
    void signupRejectsDuplicateEmail() {
        SignupRequest request =
                new SignupRequest("user@example.com", "password", "사용자");

        when(userRepository.existsByEmail(request.email())).thenReturn(true);

        assertThrows(
                AuthException.class,
                () -> authService.signup(request)
        );

        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void signupRejectsPasswordLongerThanSeventyTwoUtf8Bytes() {
        // 한글 '가'는 UTF-8 기준 3바이트이므로 25자는 75바이트입니다.
        SignupRequest request =
                new SignupRequest("user@example.com", "가".repeat(25), "사용자");

        assertThrows(
                IllegalArgumentException.class,
                () -> authService.signup(request)
        );

        verifyNoInteractions(userRepository, passwordEncoder);
    }

    @Test
    void loginReturnsAccessTokenAndConfiguredExpiry() {
        LoginRequest request =
                new LoginRequest("user@example.com", "password");

        User user = mock(User.class);
        when(user.getId()).thenReturn(17L);
        when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
        when(user.getPasswordHash()).thenReturn("encoded-password");

        when(userRepository.findByEmailForUpdate(request.email()))
                .thenReturn(Optional.of(user));
        when(passwordEncoder.matches(request.password(), "encoded-password"))
                .thenReturn(true);
        when(jwtTokenProvider.createAccessToken(17L))
                .thenReturn("test-access-token");
        when(refreshTokenService.issueInitial(17L)).thenReturn(
                new IssuedRefreshToken(17L, "raw-refresh-token", "session-id",
                        java.time.LocalDateTime.now().plusDays(30)));

        LoginSession session = authService.login(request);
        LoginResponse response = session.response();

        assertEquals("test-access-token", response.accessToken());
        assertEquals("Bearer", response.tokenType());
        assertEquals(900L, response.expiresIn());
        assertEquals("raw-refresh-token", session.refreshToken().rawToken());
    }

    @Test
    void loginRejectsUnknownEmail() {
        LoginRequest request =
                new LoginRequest("unknown@example.com", "password");

        when(userRepository.findByEmailForUpdate(request.email()))
                .thenReturn(Optional.empty());

        assertThrows(
                AuthException.class,
                () -> authService.login(request)
        );

        verifyNoInteractions(passwordEncoder, jwtTokenProvider);
    }

    @Test
    void loginRejectsIncorrectPassword() {
        LoginRequest request =
                new LoginRequest("user@example.com", "wrong-password");

        User user = mock(User.class);
        when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
        when(user.getPasswordHash()).thenReturn("encoded-password");

        when(userRepository.findByEmailForUpdate(request.email()))
                .thenReturn(Optional.of(user));
        when(passwordEncoder.matches(
                request.password(), "encoded-password")).thenReturn(false);

        assertThrows(
                AuthException.class,
                () -> authService.login(request)
        );

        verifyNoInteractions(jwtTokenProvider);
    }

    @Test
    void loginRejectsWithdrawnAccount() {
        LoginRequest request =
                new LoginRequest("user@example.com", "password");

        User user = mock(User.class);
        when(user.getAccountStatus()).thenReturn(AccountStatus.WITHDRAWN);

        when(userRepository.findByEmailForUpdate(request.email()))
                .thenReturn(Optional.of(user));

        assertThrows(
                AuthException.class,
                () -> authService.login(request)
        );

        verifyNoInteractions(passwordEncoder, jwtTokenProvider);
    }
}
