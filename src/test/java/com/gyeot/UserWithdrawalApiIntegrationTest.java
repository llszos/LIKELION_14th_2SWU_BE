package com.gyeot;

import com.gyeot.domain.user.entity.AccountStatus;
import com.gyeot.domain.user.entity.User;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.domain.user.service.UserWithdrawalService;
import com.gyeot.global.security.auth.AuthErrorCode;
import com.gyeot.global.security.auth.AuthException;
import com.gyeot.global.security.jwt.JwtTokenProvider;
import com.gyeot.global.security.refresh.repository.RefreshTokenRepository;
import com.gyeot.global.security.refresh.service.RefreshTokenGenerator;
import com.gyeot.global.security.refresh.service.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class UserWithdrawalApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private RefreshTokenGenerator refreshTokenGenerator;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserWithdrawalService userWithdrawalService;

    @BeforeEach
    void cleanDatabase() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void authenticatedUserCanWithdrawAndAllOfTheirSessionsAreRevoked() throws Exception {
        User user = createUser("withdraw");
        User otherUser = createUser("other");
        var firstSession = refreshTokenService.issueInitial(user.getId());
        var secondSession = refreshTokenService.issueInitial(user.getId());
        var otherSession = refreshTokenService.issueInitial(otherUser.getId());
        String accessToken = jwtTokenProvider.createAccessToken(user.getId());

        mockMvc.perform(delete("/api/auth/withdraw")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.containsString("Max-Age=0")));

        User withdrawn = userRepository.findById(user.getId()).orElseThrow();
        assertThat(withdrawn.getAccountStatus()).isEqualTo(AccountStatus.WITHDRAWN);
        assertThat(withdrawn.getEmail()).isNull();
        assertThat(withdrawn.getName()).isNull();
        assertThat(withdrawn.getPasswordHash()).isNull();
        assertThat(withdrawn.getWithdrawnAt()).isNotNull();

        assertThat(refreshTokenRepository.findByTokenHash(
                refreshTokenGenerator.hash(firstSession.rawToken())).orElseThrow().getRevokedAt()).isNotNull();
        assertThat(refreshTokenRepository.findByTokenHash(
                refreshTokenGenerator.hash(secondSession.rawToken())).orElseThrow().getRevokedAt()).isNotNull();
        assertThat(refreshTokenRepository.findByTokenHash(
                refreshTokenGenerator.hash(otherSession.rawToken())).orElseThrow().getRevokedAt()).isNull();

        mockMvc.perform(delete("/api/auth/withdraw")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        AuthException repeatedWithdrawal = assertThrows(AuthException.class,
                () -> userWithdrawalService.withdraw(user.getId()));
        assertEquals(AuthErrorCode.USER_ALREADY_WITHDRAWN, repeatedWithdrawal.getErrorCode());
    }

    @Test
    void withdrawalRequiresAuthentication() throws Exception {
        mockMvc.perform(delete("/api/auth/withdraw"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private User createUser(String prefix) {
        return userRepository.save(User.register(
                prefix + "-" + UUID.randomUUID() + "@example.com", "password-hash", "QA user"));
    }
}
