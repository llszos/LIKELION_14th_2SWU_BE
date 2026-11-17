package com.gyeot.domain.carerequest.controller;

import com.gyeot.domain.carerequest.dto.request.UpdateCareRequestRequest;
import com.gyeot.domain.carerequest.dto.response.ProposalAcceptResponse;
import com.gyeot.domain.carerequest.dto.response.ProposalRejectResponse;
import com.gyeot.domain.carerequest.dto.response.ProposalWithdrawResponse;
import com.gyeot.domain.carerequest.dto.response.UpdateCareRequestResponse;
import com.gyeot.domain.carerequest.enums.CancelReason;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.ProposalStatus;
import com.gyeot.domain.carerequest.exception.CareRequestErrorCode;
import com.gyeot.domain.carerequest.service.CareRequestEditService;
import com.gyeot.domain.carerequest.service.ChangeProposalService;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.GlobalExceptionHandler;
import com.gyeot.global.security.auth.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP 요청·응답 모양(입력 검증, 결과별 message, 에러 응답)만 확인합니다. */
public class CareRequestEditControllerTest {

    private static final Long USER_ID = 10L;

    private CareRequestEditService editService;
    private ChangeProposalService proposalService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        editService = mock(CareRequestEditService.class);
        proposalService = mock(ChangeProposalService.class);
        mvc = MockMvcBuilders.standaloneSetup(new CareRequestEditController(editService, proposalService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthenticatedUser(USER_ID), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // === 요청 수정 ===

    @Test
    void 수락_후_수정은_재확인_요청_문구와_수정안_정보를_돌려준다() throws Exception {
        when(editService.update(eq(21L), eq(USER_ID), any(UpdateCareRequestRequest.class)))
                .thenReturn(UpdateCareRequestResponse.proposed(21L, 6L, LocalDateTime.of(2026, 11, 9, 11, 0)));

        mvc.perform(patch("/api/care-requests/21")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"place\": \"△△병원\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("담당자에게 재확인을 요청했어요."))
                .andExpect(jsonPath("$.data.result").value("PROPOSED"))
                .andExpect(jsonPath("$.data.proposalId").value(6))
                .andExpect(jsonPath("$.data.proposalResponseDeadline").value("2026-11-09T11:00:00"));
    }

    @Test
    void 수락_전_수정은_수정안_정보가_null이다() throws Exception {
        when(editService.update(eq(21L), eq(USER_ID), any(UpdateCareRequestRequest.class)))
                .thenReturn(UpdateCareRequestResponse.applied(21L));

        mvc.perform(patch("/api/care-requests/21")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startsAt\": \"2026-11-12T10:00:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("요청을 수정했어요."))
                .andExpect(jsonPath("$.data.result").value("APPLIED"))
                .andExpect(jsonPath("$.data.proposalId").doesNotExist())
                .andExpect(jsonPath("$.data.proposalResponseDeadline").doesNotExist());
    }

    @Test
    void 바꿀_필드가_없거나_장소가_공백이면_400_INVALID_INPUT이고_서비스를_부르지_않는다() throws Exception {
        mvc.perform(patch("/api/care-requests/21").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("수정할 항목을 하나 이상 보내 주세요."));
        mvc.perform(patch("/api/care-requests/21").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"place\": \"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("장소를 입력해 주세요."));

        verifyNoInteractions(editService);
    }

    @Test
    void 장소_글자_수_초과는_전용_에러_코드로_내려간다() throws Exception {
        when(editService.update(eq(21L), eq(USER_ID), any(UpdateCareRequestRequest.class)))
                .thenThrow(new BusinessException(CareRequestErrorCode.REQUEST_PLACE_TOO_LONG));

        mvc.perform(patch("/api/care-requests/21").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"place\": \"" + "가".repeat(101) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_PLACE_TOO_LONG"))
                .andExpect(jsonPath("$.message").value("장소는 100자 이내로 입력해 주세요."));
    }

    @Test
    void 업무_규칙_위반은_에러_코드로_내려간다() throws Exception {
        when(editService.update(eq(21L), eq(USER_ID), any(UpdateCareRequestRequest.class)))
                .thenThrow(new BusinessException(CareRequestErrorCode.REQUEST_NOT_EDITABLE));

        mvc.perform(patch("/api/care-requests/21")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"place\": \"△△병원\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_NOT_EDITABLE"));
    }

    // === 수정안 철회, 재확인 수락·거절 ===

    @Test
    void 수정안_철회() throws Exception {
        when(proposalService.withdraw(21L, 6L, USER_ID))
                .thenReturn(new ProposalWithdrawResponse(21L, 6L, ProposalStatus.INVALIDATED));

        mvc.perform(post("/api/care-requests/21/change-proposals/6/withdraw"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("수정 요청을 철회했어요."))
                .andExpect(jsonPath("$.data.proposalStatus").value("INVALIDATED"));
    }

    @Test
    void 재확인_수락() throws Exception {
        when(proposalService.accept(21L, 6L, USER_ID))
                .thenReturn(new ProposalAcceptResponse(21L, 6L, ProposalStatus.ACCEPTED, CareRequestStatus.ACCEPTED));

        mvc.perform(post("/api/care-requests/21/change-proposals/6/accept"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("변경 내용을 확인했어요."))
                .andExpect(jsonPath("$.data.proposalStatus").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"));
    }

    @Test
    void 재확인_거절() throws Exception {
        when(proposalService.reject(21L, 6L, USER_ID)).thenReturn(new ProposalRejectResponse(21L, 6L,
                ProposalStatus.REJECTED, CareRequestStatus.CANCELED, CancelReason.RECONFIRMATION_REJECTED));

        mvc.perform(post("/api/care-requests/21/change-proposals/6/reject"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("변경 내용을 거절해 요청이 취소됐어요."))
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.cancelReason").value("RECONFIRMATION_REJECTED"));
    }

    @Test
    void 기한이_지난_수정안을_수락하면_409다() throws Exception {
        when(proposalService.accept(21L, 6L, USER_ID))
                .thenThrow(new BusinessException(CareRequestErrorCode.REQUEST_PROPOSAL_EXPIRED));

        mvc.perform(post("/api/care-requests/21/change-proposals/6/accept"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_PROPOSAL_EXPIRED"));
    }
}
