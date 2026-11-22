package com.gyeot.domain.carerequest.controller;

import com.gyeot.domain.carerequest.dto.response.TargetCandidatesResponse;
import com.gyeot.domain.carerequest.service.TargetCandidateService;
import com.gyeot.global.common.ApiResponse;
import com.gyeot.global.security.auth.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대상 후보 조회 API입니다. (#108, F-05·F-26)
 * 업무 규칙과 에러 판단은 Service에 있고, Controller는 값을 넘기고 공통 응답으로 감쌉니다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/care-requests")
public class TargetCandidateController {
    private final TargetCandidateService candidateService;

    /**
     * GET /api/care-requests/target-candidates?situationId=&requestId=
     * 요청 작성(H-03-02)은 situationId, 다시 부탁하기(N-06)는 requestId를 보냅니다. (둘 다 없으면 400)
     * "/{requestId}"(요청 상세)보다 이 고정 경로가 먼저 매칭됩니다.
     */
    @GetMapping("/target-candidates")
    public ApiResponse<TargetCandidatesResponse> findCandidates(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) Long situationId,
            @RequestParam(required = false) Long requestId) {
        return ApiResponse.success(candidateService.findCandidates(user.userId(), situationId, requestId));
    }
}
