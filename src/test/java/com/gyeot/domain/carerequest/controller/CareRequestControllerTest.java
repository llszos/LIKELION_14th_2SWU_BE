package com.gyeot.domain.carerequest.controller;

import com.gyeot.domain.carerequest.dto.request.CreateCareRequestRequest;
import com.gyeot.domain.carerequest.dto.response.CareRequestDetailResponse;
import com.gyeot.domain.carerequest.dto.response.CareRequestDetailResponse.UserView;
import com.gyeot.domain.carerequest.dto.response.CreateCareRequestResponse;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.RequestType;
import com.gyeot.domain.carerequest.exception.CareRequestErrorCode;
import com.gyeot.domain.carerequest.query.RequestDisplayStatus;
import com.gyeot.domain.carerequest.service.CareRequestCreateService;
import com.gyeot.domain.carerequest.service.CareRequestDetailService;
import com.gyeot.domain.carerequest.service.RequestAction;
import com.gyeot.domain.carerequest.service.RequestViewerRole;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP 요청·응답 모양(201, 입력 검증, message, 에러 응답)만 확인합니다. */
public class CareRequestControllerTest {

    private static final Long USER_ID = 10L;
    private static final String VALID_BODY = """
            {"situationId": 3, "helpType": "HOSPITAL",
             "startsAt": "2026-11-12T14:00:00", "endsAt": "2026-11-12T17:00:00",
             "place": "○○병원", "targetUserId": 7, "informationIds": [12, 15]}
            """;

    private CareRequestCreateService createService;
    private CareRequestDetailService detailService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        createService = mock(CareRequestCreateService.class);
        detailService = mock(CareRequestDetailService.class);
        mvc = MockMvcBuilders.standaloneSetup(new CareRequestController(createService, detailService))
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

    // === 요청 생성 ===

    @Test
    void 요청을_만들면_201과_요청_정보를_돌려준다() throws Exception {
        when(createService.create(eq(USER_ID), any(CreateCareRequestRequest.class)))
                .thenReturn(new CreateCareRequestResponse(21L, CareRequestStatus.REQUESTED,
                        LocalDateTime.of(2026, 11, 11, 9, 0)));

        mvc.perform(post("/api/care-requests").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("요청을 보냈어요."))
                .andExpect(jsonPath("$.data.requestId").value(21))
                .andExpect(jsonPath("$.data.status").value("REQUESTED"))
                .andExpect(jsonPath("$.data.responseDeadline").value("2026-11-11T09:00:00"));
    }

    @Test
    void 필수값이_없으면_서비스를_부르지_않고_400_INVALID_INPUT이다() throws Exception {
        mvc.perform(post("/api/care-requests").contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"targetUserId\": 7, ", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("요청할 사람을 선택해 주세요."));
        verifyNoInteractions(createService);
    }

    @Test
    void 장소가_공백이면_400_INVALID_INPUT이다() throws Exception {
        mvc.perform(post("/api/care-requests").contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("○○병원", "   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(createService);
    }

    @Test
    void 공개_항목을_보내지_않으면_400_INVALID_INPUT이다() throws Exception {
        mvc.perform(post("/api/care-requests").contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace(", \"informationIds\": [12, 15]", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void 없는_도움_종류면_400_INVALID_INPUT이다() throws Exception {
        mvc.perform(post("/api/care-requests").contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("HOSPITAL", "SWIMMING")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void 서비스_에러는_공통_에러_응답으로_돌려준다() throws Exception {
        when(createService.create(eq(USER_ID), any(CreateCareRequestRequest.class)))
                .thenThrow(new BusinessException(CareRequestErrorCode.REQUEST_SITUATION_ENDED));

        mvc.perform(post("/api/care-requests").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.code").value("REQUEST_SITUATION_ENDED"));
    }

    // === 요청 상세 ===

    @Test
    void 요청_상세를_200으로_돌려주고_비어_있는_필드는_null로_내려간다() throws Exception {
        when(detailService.getDetail(21L, USER_ID)).thenReturn(new CareRequestDetailResponse(
                21L, null, RequestType.GENERAL, HelpType.MEAL, null, "집",
                LocalDateTime.of(2026, 11, 12, 14, 0), LocalDateTime.of(2026, 11, 12, 17, 0),
                CareRequestStatus.REQUESTED, RequestDisplayStatus.WAITING, null, RequestViewerRole.TARGET,
                new UserView(2L, "김지우"), null, null, null, null, null, null,
                List.of(RequestAction.RESPOND)));

        mvc.perform(get("/api/care-requests/21"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requestId").value(21))
                .andExpect(jsonPath("$.data.viewerRole").value("TARGET"))
                .andExpect(jsonPath("$.data.displayStatus").value("WAITING"))
                .andExpect(jsonPath("$.data.requester.name").value("김지우"))
                .andExpect(jsonPath("$.data.description").isEmpty())
                .andExpect(jsonPath("$.data.currentAssignment").isEmpty())
                .andExpect(jsonPath("$.data.actions[0]").value("RESPOND"));
    }

    @Test
    void 대상자의_보호자_여부와_마감_여부는_isGuardian_closed_이름_그대로_내려간다() throws Exception {
        CareRequestDetailResponse.TargetView target = new CareRequestDetailResponse.TargetView(7L, "박민재", true);
        when(detailService.getDetail(21L, USER_ID)).thenReturn(new CareRequestDetailResponse(
                21L, null, RequestType.GENERAL, HelpType.MEAL, null, "집",
                LocalDateTime.of(2026, 11, 12, 14, 0), LocalDateTime.of(2026, 11, 12, 17, 0),
                CareRequestStatus.REQUESTED, RequestDisplayStatus.WAITING, null, RequestViewerRole.REQUESTER,
                new UserView(2L, "김지우"),
                new CareRequestDetailResponse.CurrentAssignmentView(34L, target, null,
                        LocalDateTime.of(2026, 11, 11, 9, 0), null, false, null),
                null, List.of(new CareRequestDetailResponse.AssignmentView(1, target, null, null)),
                24, null, null, List.of(RequestAction.CANCEL, RequestAction.EDIT)));

        mvc.perform(get("/api/care-requests/21"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentAssignment.target.isGuardian").value(true))
                .andExpect(jsonPath("$.data.currentAssignment.closed").value(false))
                .andExpect(jsonPath("$.data.assignments[0].order").value(1))
                .andExpect(jsonPath("$.data.assignments[0].target.isGuardian").value(true))
                .andExpect(jsonPath("$.data.responseDeadlineHours").value(24));
    }

    @Test
    void 관련_없는_요청이면_404다() throws Exception {
        when(detailService.getDetail(21L, USER_ID))
                .thenThrow(new BusinessException(CareRequestErrorCode.REQUEST_NOT_FOUND));

        mvc.perform(get("/api/care-requests/21"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REQUEST_NOT_FOUND"));
    }
}
