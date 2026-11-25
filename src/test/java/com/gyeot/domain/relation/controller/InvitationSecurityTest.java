package com.gyeot.domain.relation.controller;

import com.gyeot.domain.relation.dto.response.InvitationDetailsResponse;
import com.gyeot.domain.relation.entity.InvitationStatus;
import com.gyeot.domain.relation.entity.InvitationType;
import com.gyeot.domain.relation.service.InvitationService;
import com.gyeot.domain.relation.service.SentInvitationListService;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.exception.GlobalExceptionHandler;
import com.gyeot.global.security.ApiAccessDeniedHandler;
import com.gyeot.global.security.ApiAuthenticationEntryPoint;
import com.gyeot.global.security.PublicApiRequestMatcher;
import com.gyeot.global.security.SecurityConfig;
import com.gyeot.global.security.csrf.RefreshCookieCsrfMatcher;
import com.gyeot.global.security.cookie.RefreshTokenCookieService;
import com.gyeot.global.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InvitationController.class)
@Import({SecurityConfig.class, PublicApiRequestMatcher.class, RefreshCookieCsrfMatcher.class,
        ApiAuthenticationEntryPoint.class, ApiAccessDeniedHandler.class, GlobalExceptionHandler.class})
class InvitationSecurityTest {

    @Autowired MockMvc mvc;
    @MockitoBean JwtTokenProvider tokens;
    @MockitoBean UserRepository users;
    @MockitoBean InvitationService invitationService;
    @MockitoBean SentInvitationListService sentInvitationListService;
    @MockitoBean RefreshTokenCookieService refreshCookies;

    @Test
    void 보낸_초대_목록은_미인증_요청에서_401을_반환한다() throws Exception {
        mvc.perform(get("/api/invitations/sent").servletPath("/api/invitations/sent"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(sentInvitationListService);
    }

    @Test
    void 초대_링크_조회는_공개로_유지하고_보낸_초대_목록은_인증을_요구한다() {
        PublicApiRequestMatcher matcher = new PublicApiRequestMatcher();

        assertTrue(matcher.matches(getRequest("/api/invitations/public-token")));
        assertFalse(matcher.matches(getRequest("/api/invitations/sent")));
    }

    @Test
    void 초대_링크_상세는_비로그인_상태에서도_조회할_수_있다() throws Exception {
        when(invitationService.getInvitation("public-token", null)).thenReturn(
                new InvitationDetailsResponse(
                        InvitationType.MY_PERSON,
                        new InvitationDetailsResponse.Inviter(1L, "서연"),
                        "함께 해요",
                        null,
                        LocalDateTime.of(2026, 11, 24, 12, 0),
                        InvitationStatus.PENDING,
                        true,
                        null
                )
        );

        mvc.perform(get("/api/invitations/public-token").servletPath("/api/invitations/public-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.inviter.name").value("서연"));
    }

    private HttpServletRequest getRequest(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return request;
    }
}
