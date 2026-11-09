package com.gyeot.global.security.jwt;

import com.gyeot.domain.user.entity.AccountStatus;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.security.ApiAuthenticationEntryPoint;
import com.gyeot.global.security.PublicApiRequestMatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JwtAuthenticationFilterTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private JwtTokenProvider tokenProvider;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(Base64.getEncoder().encodeToString(new byte[32]));
        properties.setIssuer("test");
        tokenProvider = new JwtTokenProvider(properties, Clock.systemUTC());
        filter = new JwtAuthenticationFilter(
                tokenProvider,
                userRepository(false),
                new PublicApiRequestMatcher(),
                new ApiAuthenticationEntryPoint(jsonMapper));
    }

    @Test
    void ignoresInvalidAccessTokenOnPublicLoginEndpoint() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/auth/login");
        request.addHeader("Authorization", "Bearer expired-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void returnsCommonUnauthorizedResponseForInvalidProtectedToken() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/private");
        request.addHeader("Authorization", "Bearer broken-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
        var errorBody = jsonMapper.readTree(response.getContentAsString());
        assertEquals("error", errorBody.get("status").asText());
        assertEquals("UNAUTHORIZED", errorBody.get("code").asText());
    }

    @Test
    void returnsCommonUnauthorizedResponseForInactiveAccount() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/private");
        request.addHeader("Authorization", "Bearer " + tokenProvider.createAccessToken(21L));
        filter = new JwtAuthenticationFilter(
                tokenProvider,
                userRepository(false),
                new PublicApiRequestMatcher(),
                new ApiAuthenticationEntryPoint(jsonMapper));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
        assertEquals("UNAUTHORIZED", jsonMapper.readTree(response.getContentAsString())
                .get("code").asText());
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }

    private UserRepository userRepository(boolean active) {
        return (UserRepository) Proxy.newProxyInstance(
                UserRepository.class.getClassLoader(),
                new Class<?>[]{UserRepository.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("existsByIdAndAccountStatus")) {
                        return active && arguments[1] == AccountStatus.ACTIVE;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
