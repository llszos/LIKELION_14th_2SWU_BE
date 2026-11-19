package com.gyeot.domain.access.controller;

import com.gyeot.domain.access.dto.response.ReceivedSharingResponse;
import com.gyeot.domain.access.dto.response.ReceivedSharingResponse.ReceivedSharing;
import com.gyeot.domain.access.dto.response.SharedItemName;
import com.gyeot.domain.access.dto.response.SharingListResponse;
import com.gyeot.domain.access.dto.response.SharingListResponse.Receiver;
import com.gyeot.domain.access.dto.response.SharingListResponse.SharingCard;
import com.gyeot.domain.access.dto.response.SharingReallowResponse;
import com.gyeot.domain.access.dto.response.SharingRevokeResponse;
import com.gyeot.domain.access.dto.response.SharingStatus;
import com.gyeot.domain.access.service.ReceivedSharingService;
import com.gyeot.domain.access.service.SharingListService;
import com.gyeot.domain.access.service.SharingReallowService;
import com.gyeot.domain.access.service.SharingRevokeService;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.exception.SafetyCardErrorCode;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.GlobalExceptionHandler;
import com.gyeot.global.security.auth.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공유 관리 API의 실제 MVC 요청 변환(로그인 사용자, 쿼리 값, 응답 형태, 입력 오류)을 확인합니다. DB나 Docker는 필요 없습니다.
 * Security 필터 없이 Controller만 띄우므로, 로그인 상태는 JwtAuthenticationFilter가 하는 것처럼 SecurityContext에 직접 넣습니다.
 */
class SharingControllerTest {

    private static final Long USER_ID = 20L; // 로그인한 사용자 (받은 정보에서는 받는 사람, 그 외에는 정보 주인)

    private SharingListService sharingListService;
    private ReceivedSharingService receivedSharingService;
    private SharingRevokeService sharingRevokeService;
    private SharingReallowService sharingReallowService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        sharingListService = mock(SharingListService.class);
        receivedSharingService = mock(ReceivedSharingService.class);
        sharingRevokeService = mock(SharingRevokeService.class);
        sharingReallowService = mock(SharingReallowService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SharingController(
                        sharingListService, receivedSharingService, sharingRevokeService, sharingReallowService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()) // @AuthenticationPrincipal 값을 채워 주는 처리기
                .build();

        // 사용자 20번으로 로그인한 상태 (JwtAuthenticationFilter가 토큰 검증 후 넣는 값과 같은 형태)
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthenticatedUser(USER_ID), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext(); // 다른 테스트에 로그인 상태가 남지 않게 정리
    }

    // === 공유 중 목록 조회 ===

    @Test
    void 공유_중_목록을_명세_형태로_내려준다() throws Exception {
        // 로그인 사용자(정보 주인)와 userId 없음(전체)이 Service에 그대로 넘어올 때만 응답을 돌려줌
        when(sharingListService.getSharings(USER_ID, null)).thenReturn(new SharingListResponse(List.of(
                new SharingCard(40L, 31L, new Receiver(7L, "민재"), HelpType.HOSPITAL,
                        LocalDateTime.of(2026, 10, 2, 14, 0), LocalDateTime.of(2026, 10, 2, 18, 0),
                        SharingStatus.REVOKED, true,
                        List.of(SharedItemName.of(InformationType.MEDICATION, "혈압약"))))));

        mvc.perform(get("/api/sharings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.items[0].assignmentId").value(40))
                .andExpect(jsonPath("$.data.items[0].requestId").value(31))
                .andExpect(jsonPath("$.data.items[0].receiver.userId").value(7))
                .andExpect(jsonPath("$.data.items[0].receiver.name").value("민재"))
                .andExpect(jsonPath("$.data.items[0].helpType").value("HOSPITAL"))
                .andExpect(jsonPath("$.data.items[0].startsAt").value("2026-10-02T14:00:00"))
                .andExpect(jsonPath("$.data.items[0].accessUntil").value("2026-10-02T18:00:00"))
                .andExpect(jsonPath("$.data.items[0].status").value("REVOKED"))
                .andExpect(jsonPath("$.data.items[0].canReallow").value(true))
                .andExpect(jsonPath("$.data.items[0].sharedItems[0].informationType").value("MEDICATION"))
                .andExpect(jsonPath("$.data.items[0].sharedItems[0].typeName").value(InformationType.MEDICATION.getDescription()))
                .andExpect(jsonPath("$.data.items[0].sharedItems[0].title").value("혈압약"));
    }

    @Test
    void 공유_중_목록에_userId를_넣으면_그_사람_기준으로_조회한다() throws Exception {
        when(sharingListService.getSharings(USER_ID, 7L)).thenReturn(new SharingListResponse(List.of()));

        mvc.perform(get("/api/sharings").param("userId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void 공유_중_목록의_userId가_숫자가_아니면_400이다() throws Exception {
        mvc.perform(get("/api/sharings").param("userId", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    // === 받은 정보 조회 ===

    @Test
    void 받은_정보를_명세_형태로_내려준다() throws Exception {
        // 로그인 사용자(받는 사람)와 쿼리의 userId(정보 주인)가 Service에 그대로 넘어올 때만 응답을 돌려줌
        when(receivedSharingService.getReceived(USER_ID, 10L)).thenReturn(new ReceivedSharingResponse(List.of(
                new ReceivedSharing(45L, HelpType.HOSPITAL, LocalDateTime.of(2026, 10, 8, 10, 0),
                        LocalDateTime.of(2026, 10, 8, 20, 0),
                        List.of(SharedItemName.of(InformationType.ALLERGY, "페니실린"))))));

        mvc.perform(get("/api/sharings/received").param("userId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.items[0].requestId").value(45))
                .andExpect(jsonPath("$.data.items[0].helpType").value("HOSPITAL"))
                .andExpect(jsonPath("$.data.items[0].startsAt").value("2026-10-08T10:00:00"))
                .andExpect(jsonPath("$.data.items[0].accessUntil").value("2026-10-08T20:00:00"))
                .andExpect(jsonPath("$.data.items[0].sharedItems[0].informationType").value("ALLERGY"))
                .andExpect(jsonPath("$.data.items[0].sharedItems[0].typeName").value(InformationType.ALLERGY.getDescription()))
                .andExpect(jsonPath("$.data.items[0].sharedItems[0].title").value("페니실린"));
    }

    @Test
    void 받은_정보가_없으면_빈_배열이다() throws Exception {
        when(receivedSharingService.getReceived(USER_ID, 10L)).thenReturn(new ReceivedSharingResponse(List.of()));

        mvc.perform(get("/api/sharings/received").param("userId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void userId가_없으면_400이다() throws Exception {
        mvc.perform(get("/api/sharings/received"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void userId가_숫자가_아니면_400이다() throws Exception {
        mvc.perform(get("/api/sharings/received").param("userId", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    // === 공유 직접 철회 ===

    @Test
    void 직접_철회하면_철회된_카드_상태를_명세_형태로_내려준다() throws Exception {
        // 로그인 사용자(정보 주인)와 URL의 카드 ID가 Service에 그대로 넘어올 때만 응답을 돌려줌
        when(sharingRevokeService.revoke(USER_ID, 40L)).thenReturn(SharingRevokeResponse.revoked(40L, true));

        mvc.perform(post("/api/sharings/40/revoke"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("공유를 철회했습니다."))
                .andExpect(jsonPath("$.data.assignmentId").value(40))
                .andExpect(jsonPath("$.data.status").value("REVOKED"))
                .andExpect(jsonPath("$.data.canReallow").value(true));
    }

    @Test
    void 철회할_카드가_없으면_404다() throws Exception {
        when(sharingRevokeService.revoke(USER_ID, 40L))
                .thenThrow(new BusinessException(SafetyCardErrorCode.SHARING_NOT_FOUND));

        mvc.perform(post("/api/sharings/40/revoke"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHARING_NOT_FOUND"));
    }

    @Test
    void 철회의_assignmentId가_숫자가_아니면_400이다() throws Exception {
        mvc.perform(post("/api/sharings/abc/revoke"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    // === 공유 다시 허용 ===

    @Test
    void 다시_허용하면_공유_중_상태와_다시_공개된_항목을_명세_형태로_내려준다() throws Exception {
        when(sharingReallowService.reallow(USER_ID, 40L)).thenReturn(SharingReallowResponse.reallowed(40L,
                List.of(SharedItemName.of(InformationType.MEDICATION, "혈압약"))));

        mvc.perform(post("/api/sharings/40/reallow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("다시 허용했습니다."))
                .andExpect(jsonPath("$.data.assignmentId").value(40))
                .andExpect(jsonPath("$.data.status").value("SHARING"))
                .andExpect(jsonPath("$.data.sharedItems[0].informationType").value("MEDICATION"))
                .andExpect(jsonPath("$.data.sharedItems[0].typeName").value(InformationType.MEDICATION.getDescription()))
                .andExpect(jsonPath("$.data.sharedItems[0].title").value("혈압약"));
    }

    @Test
    void 다시_허용할_수_없으면_409다() throws Exception {
        when(sharingReallowService.reallow(USER_ID, 40L))
                .thenThrow(new BusinessException(SafetyCardErrorCode.SHARING_REALLOW_NOT_ALLOWED));

        mvc.perform(post("/api/sharings/40/reallow"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHARING_REALLOW_NOT_ALLOWED"));
    }
}
