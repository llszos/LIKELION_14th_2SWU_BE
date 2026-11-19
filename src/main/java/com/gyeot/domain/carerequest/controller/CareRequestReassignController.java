package com.gyeot.domain.carerequest.controller;

import com.gyeot.domain.carerequest.dto.request.ReassignRequest;
import com.gyeot.domain.carerequest.dto.response.ReassignResponse;
import com.gyeot.domain.carerequest.service.CareRequestReassignService;
import com.gyeot.global.common.ApiResponse;
import com.gyeot.global.security.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 다시 부탁하기 API입니다. (#71, F-05·F-14)
 * 업무 규칙과 에러 판단은 Service에 있고, Controller는 값을 넘기고 공통 응답으로 감쌉니다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/care-requests")
public class CareRequestReassignController {
    private final CareRequestReassignService reassignService;

    /** POST /api/care-requests/{requestId}/assignments - 담당자 없는 요청을 다른 사람에게 다시 부탁 (201) */
    @PostMapping("/{requestId}/assignments")
    public ResponseEntity<ApiResponse<ReassignResponse>> reassign(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long requestId,
            @Valid @RequestBody ReassignRequest request) { // 대상·공개 항목 누락이면 400 INVALID_INPUT
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(reassignService.reassign(requestId, user.userId(), request),
                        "다른 분께 다시 부탁했어요."));
    }
}
